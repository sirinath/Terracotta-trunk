/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.lockmanager.impl;

import com.tc.async.api.Sink;
import com.tc.exception.TCInternalError;
import com.tc.exception.TCLockUpgradeNotSupportedError;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.L2LockStatsManager;
import com.tc.net.NodeID;
import com.tc.object.lockmanager.api.LockContext;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.LockLevel;
import com.tc.object.lockmanager.api.ServerThreadID;
import com.tc.object.lockmanager.api.TCLockTimer;
import com.tc.object.lockmanager.api.ThreadID;
import com.tc.object.lockmanager.api.TimerCallback;
import com.tc.object.lockmanager.impl.LockHolder;
import com.tc.object.net.DSOChannelManager;
import com.tc.object.tx.TimerSpec;
import com.tc.objectserver.context.LockResponseContext;
import com.tc.objectserver.lockmanager.api.LockMBean;
import com.tc.objectserver.lockmanager.api.LockWaitContext;
import com.tc.objectserver.lockmanager.api.NotifiedWaiters;
import com.tc.objectserver.lockmanager.api.ServerLockRequest;
import com.tc.objectserver.lockmanager.api.TCIllegalMonitorStateException;
import com.tc.objectserver.lockmanager.api.Waiter;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;

public class Lock {
  private static final TCLogger                     logger              = TCLogging.getLogger(Lock.class);
  private static final Map                          EMPTY_MAP           = Collections.EMPTY_MAP;

  private final static boolean                      LOCK_LEASE_ENABLE   = TCPropertiesImpl
                                                                            .getProperties()
                                                                            .getBoolean(
                                                                                        TCPropertiesConsts.L2_LOCKMANAGER_GREEDY_LEASE_ENABLED);
  private final static int                          LOCK_LEASE_TIME     = TCPropertiesImpl
                                                                            .getProperties()
                                                                            .getInt(
                                                                                    TCPropertiesConsts.L2_LOCKMANAGER_GREEDY_LEASE_LEASETIME_INMILLS);
  public final static Lock                          NULL_LOCK           = new Lock(
                                                                                   LockID.NULL_ID,
                                                                                   LockManagerImpl.ALTRUISTIC_LOCK_POLICY,
                                                                                   ServerThreadContextFactory.DEFAULT_FACTORY,
                                                                                   L2LockStatsManager.NULL_LOCK_STATS_MANAGER,
                                                                                   "");

  // These settings are optimized for the assumed common case of small size (and often size 1) of the various maps that
  // comprise the lock accounting. With these settings the maps will not grow/rehash until their size reaches the
  // current capacity which is space tradeoff from the default settings that kick in at 75% full
  private static final int                          MAP_SIZE            = 1;
  private static final float                        LOAD_FACTOR         = 1F;

  private final Map<NodeID, Holder>                 greedyHolders       = new HashMap(MAP_SIZE, LOAD_FACTOR);
  private final Map<ServerThreadContext, Holder>    holders             = new HashMap(MAP_SIZE, LOAD_FACTOR);
  private final LockID                              lockID;
  private final ServerThreadContextFactory          threadContextFactory;
  private final L2LockStatsManager                  lockStatsManager;
  private final String                              lockType;

  private Map<TimerKey, TimerTask>                  timers              = EMPTY_MAP;
  private Map<ServerThreadContext, Request>         pendingLockRequests = EMPTY_MAP;
  private Map<ServerThreadContext, LockWaitContext> waiters             = EMPTY_MAP;

  private int                                       level;
  private boolean                                   recalled            = false;
  private int                                       lockPolicy;

  // real constructor used by lock manager
  Lock(final LockID lockID, final ServerThreadContext txn, final int lockLevel, final String lockType,
       final Sink lockResponseSink, final int lockPolicy, final ServerThreadContextFactory threadContextFactory,
       final L2LockStatsManager lockStatsManager) {
    this(lockID, lockPolicy, threadContextFactory, lockStatsManager, lockType);
    requestLock(txn, lockLevel, lockResponseSink);
  }

  // real constructor used by lock manager when re-establishing waits and lock holds on restart.
  Lock(final LockID lockID, final ServerThreadContext txn, final int lockPolicy,
       final ServerThreadContextFactory threadContextFactory, final L2LockStatsManager lockStatsManager) {
    this(lockID, lockPolicy, threadContextFactory, lockStatsManager, "");
  }

  // used in tests and in query lock when Locks don't exists.
  Lock(final LockID lockID) {
    this(lockID, LockManagerImpl.ALTRUISTIC_LOCK_POLICY, ServerThreadContextFactory.DEFAULT_FACTORY,
         L2LockStatsManager.NULL_LOCK_STATS_MANAGER, "");
  }

  private Lock(final LockID lockID, final int lockPolicy, final ServerThreadContextFactory threadContextFactory,
               final L2LockStatsManager lockStatsManager, String lockType) {
    this.lockID = lockID;
    this.lockType = lockType;
    this.lockPolicy = lockPolicy;
    this.threadContextFactory = threadContextFactory;
    this.lockStatsManager = lockStatsManager;
  }

  static LockResponseContext createLockRejectedResponseContext(final LockID lockID, final ServerThreadID threadID,
                                                               final int level) {
    return new LockResponseContext(lockID, threadID.getNodeID(), threadID.getClientThreadID(), level,
                                   LockResponseContext.LOCK_NOT_AWARDED);
  }

  static LockResponseContext createLockAwardResponseContext(final LockID lockID, final ServerThreadID threadID,
                                                            final int level) {
    LockResponseContext lrc = new LockResponseContext(lockID, threadID.getNodeID(), threadID.getClientThreadID(),
                                                      level, LockResponseContext.LOCK_AWARD);
    return lrc;
  }

  static LockResponseContext createLockRecallResponseContext(final LockID lockID, final ServerThreadID threadID,
                                                             final int level) {
    if (LOCK_LEASE_ENABLE) {
      return new LockResponseContext(lockID, threadID.getNodeID(), threadID.getClientThreadID(), level,
                                     LockResponseContext.LOCK_RECALL, LOCK_LEASE_TIME);
    } else {
      return new LockResponseContext(lockID, threadID.getNodeID(), threadID.getClientThreadID(), level,
                                     LockResponseContext.LOCK_RECALL);
    }
  }

  static LockResponseContext createLockWaitTimeoutResponseContext(final LockID lockID, final ServerThreadID threadID,
                                                                  final int level) {
    return new LockResponseContext(lockID, threadID.getNodeID(), threadID.getClientThreadID(), level,
                                   LockResponseContext.LOCK_WAIT_TIMEOUT);
  }

  static LockResponseContext createLockQueriedResponseContext(final LockID lockID, final ServerThreadID threadID,
                                                              final int level, final int lockRequestQueueLength,
                                                              final Collection greedyHolders, final Collection holders,
                                                              final Collection waiters) {
    return new LockResponseContext(lockID, threadID.getNodeID(), threadID.getClientThreadID(), level,
                                   lockRequestQueueLength, greedyHolders, holders, waiters,
                                   LockResponseContext.LOCK_INFO);
  }

  private static Request createRequest(final ServerThreadContext txn, final int lockLevel, final Sink lockResponseSink,
                                       final TimerSpec timeout, final boolean isBlock) {
    Request request = null;
    if (isBlock) {
      request = new TryLockRequest(txn, lockLevel, lockResponseSink, timeout);
    } else {
      request = new Request(txn, lockLevel, lockResponseSink);
    }
    return request;
  }

  synchronized LockMBean getMBean(final DSOChannelManager channelManager) {
    int count;
    LockHolder[] holds = new LockHolder[this.holders.size()];
    ServerLockRequest[] reqs = new ServerLockRequest[this.pendingLockRequests.size()];
    Waiter[] waits = new Waiter[this.waiters.size()];

    count = 0;
    for (Holder h : this.holders.values()) {
      NodeID cid = h.getNodeID();
      holds[count] = new LockHolder(h.getLockID(), channelManager.getChannelAddress(cid), h.getThreadID(), h
          .getLockLevel(), h.getTimestamp());
      holds[count++].lockAcquired(h.getTimestamp());
    }

    count = 0;
    for (Request r : this.pendingLockRequests.values()) {
      NodeID cid = r.getRequesterID();
      reqs[count++] = new ServerLockRequest(channelManager.getChannelAddress(cid), r.getSourceID(), r.getLockLevel(), r
          .getTimestamp());
    }

    count = 0;
    for (LockWaitContext wc : this.waiters.values()) {
      NodeID cid = wc.getNodeID();
      waits[count++] = new Waiter(channelManager.getChannelAddress(cid), wc.getThreadID(), wc.getTimerSpec(), wc
          .getTimestamp());
    }

    return new LockMBeanImpl(lockID, holds, reqs, waits);
  }

  synchronized void queryLock(final ServerThreadContext txn, final Sink lockResponseSink) {

    // TODO:
    // The Remote Lock Manager needs to ask the client for lock information when greedy lock is awarded.
    // Currently, the Remote Lock Manager responds to queryLock by looking at the server only.
    lockResponseSink.add(createLockQueriedResponseContext(this.lockID, txn.getId(), this.level,
                                                          this.pendingLockRequests.size(), this.greedyHolders.values(),
                                                          this.holders.values(), this.waiters.values()));
  }

  boolean tryRequestLock(final ServerThreadContext txn, final int requestedLockLevel,
                         final TimerSpec lockRequestTimeout, final TCLockTimer waitTimer, final TimerCallback callback,
                         final Sink lockResponseSink) {
    return requestLock(txn, requestedLockLevel, lockResponseSink, true, lockRequestTimeout, waitTimer, callback);
  }

  boolean requestLock(final ServerThreadContext txn, final int requestedLockLevel, final Sink lockResponseSink) {
    return requestLock(txn, requestedLockLevel, lockResponseSink, false, null, null, null);
  }

  // XXX:: UPGRADE Requests can come in with requestLockLevel == UPGRADE on a notified wait during server crash
  synchronized boolean requestLock(final ServerThreadContext txn, final int requestedLockLevel,
                                   final Sink lockResponseSink, final boolean noBlock,
                                   final TimerSpec lockRequestTimeout, final TCLockTimer waitTimer,
                                   final TimerCallback callback) {

    if (holdsReadLock(txn) && LockLevel.isWrite(requestedLockLevel)) {
      // lock upgrade is not supported; it should have been rejected by the client.
      throw new TCLockUpgradeNotSupportedError(
                                               "Lock upgrade is not supported. The request should have been rejected by the client. Your client may be using an older version of tc.jar");
    }

    if (waiters.containsKey(txn)) throw new AssertionError("Attempt to request a lock in a Thread "
                                                           + "that is already part of the wait set. lock = " + this);

    recordLockRequestStat(txn.getId().getNodeID(), txn.getId().getClientThreadID());
    // debug("requestLock - BEGIN -", txn, ",", LockLevel.toString(requestedLockLevel));

    Holder holder = getHolder(txn);
    if (noBlock && !lockRequestTimeout.needsToWait() && holder == null
        && (requestedLockLevel != LockLevel.READ || !this.isRead()) && (getHoldersCount() > 0 || hasGreedyHolders())) {

      // Send out a lock recall in case this is a greedy lock that's not held by the
      // requesting context. Without this, repeated tryLock requests will never issue
      // a recall and never be granted due to the nature of greedy locks.
      if (isPolicyGreedy() && hasGreedyHolders() && !holdsGreedyLock(txn)) {
        recall(requestedLockLevel);
        queueRequest(txn, requestedLockLevel, lockResponseSink, noBlock, lockRequestTimeout, waitTimer, callback);

        // clean up the lock request immediately since the tryLock has no timeout
        pendingLockRequests.remove(txn);
        cannotAwardAndRespond(txn, requestedLockLevel, lockResponseSink);
      } else if (!isPolicyGreedy() || !canAwardGreedilyOnTheClient(txn, requestedLockLevel)) {
        // These requests are the ones in the wire when the greedy lock was given out to the client.
        // We can safely ignore it as the clients will be able to award it locally.
        logger.debug(lockID + " : Lock.requestLock() : Ignoring the Lock request(" + txn + ","
                     + LockLevel.toString(requestedLockLevel)
                     + ") message from the a client that has the lock greedily.");
        return false;
      }

      return false;
    }

    // It is an error (probably originating from the client side) to
    // request a lock you already hold
    if (holder != null) {
      if (LockLevel.NIL_LOCK_LEVEL != (holder.getLockLevel() & requestedLockLevel)) {
        // formatting
        throw new AssertionError("Client requesting already held lock! holder=" + holder + ", lock=" + this);
      }
    }

    if (isPolicyGreedy()) {
      if (canAwardGreedilyOnTheClient(txn, requestedLockLevel)) {
        // These requests are the ones in the wire when the greedy lock was given out to the client.
        // We can safely ignore it as the clients will be able to award it locally.
        logger.debug(lockID + " : Lock.requestLock() : Ignoring the Lock request(" + txn + ","
                     + LockLevel.toString(requestedLockLevel)
                     + ") message from the a client that has the lock greedily.");
        return false;
      } else if (recalled) {
        // add to pending until recall process is complete, those who hold the lock greedily will send the
        // pending state during recall commit.
        if (!holdsGreedyLock(txn)) {
          queueRequest(txn, requestedLockLevel, lockResponseSink, noBlock, lockRequestTimeout, waitTimer, callback);
        }
        return false;
      }
    }

    // Lock granting logic:
    // 0. If no one is holding this lock, go ahead and award it
    // 1. If only a read lock is held and no write locks are pending, and another read
    // (and only read) lock is requested, award it. If Write locks are pending, we dont want to
    // starve the WRITES by keeping on awarding READ Locks.
    // 2. Else the request must be queued (ie. added to pending list)

    if ((getHoldersCount() == 0) || ((!hasPending()) && ((requestedLockLevel == LockLevel.READ) && this.isRead()))) {
      // (0, 1) uncontended or additional read lock
      if (isPolicyGreedy() && ((requestedLockLevel == LockLevel.READ) || (getWaiterCount() == 0))) {
        awardGreedyAndRespond(txn, requestedLockLevel, lockResponseSink);
      } else {
        awardAndRespond(txn, txn.getId().getClientThreadID(), requestedLockLevel, lockResponseSink);
      }
    } else {
      // (2) queue request
      if (isPolicyGreedy() && hasGreedyHolders()) {
        recall(requestedLockLevel);
      }
      if (!holdsGreedyLock(txn)) {
        queueRequest(txn, requestedLockLevel, lockResponseSink, noBlock, lockRequestTimeout, waitTimer, callback);
      }
      return false;
    }

    return true;
  }

  private void queueRequest(final ServerThreadContext txn, final int requestedLockLevel, final Sink lockResponseSink,
                            final boolean noBlock, final TimerSpec lockRequesttimeout, final TCLockTimer waitTimer,
                            final TimerCallback callback) {
    if (noBlock) {
      addPendingTryLockRequest(txn, requestedLockLevel, lockRequesttimeout, lockResponseSink, waitTimer, callback);
    } else {
      addPendingLockRequest(txn, requestedLockLevel, lockResponseSink);
    }
  }

  synchronized void addRecalledHolder(final ServerThreadContext txn, final int lockLevel) {
    // debug("addRecalledHolder - BEGIN -", txn, ",", LockLevel.toString(lockLevel));
    if (!LockLevel.isWrite(level) && LockLevel.isWrite(lockLevel)) {
      // Client issued a WRITE lock without holding a GREEDY WRITE. Bug in the client.
      throw new AssertionError("Client issued a WRITE lock without holding a GREEDY WRITE !");
    }
    recordLockRequestStat(txn.getId().getNodeID(), txn.getId().getClientThreadID());
    awardLock(txn, txn.getId().getClientThreadID(), lockLevel);
  }

  synchronized void addRecalledPendingRequest(final ServerThreadContext txn, final int lockLevel,
                                              final Sink lockResponseSink) {
    // debug("addRecalledPendingRequest - BEGIN -", txn, ",", LockLevel.toString(lockLevel));
    recordLockRequestStat(txn.getId().getNodeID(), txn.getId().getClientThreadID());
    addPendingLockRequest(txn, lockLevel, lockResponseSink);
  }

  synchronized void addRecalledTryLockPendingRequest(final ServerThreadContext txn, final int lockLevel,
                                                     final TimerSpec lockRequestTimeout, final Sink lockResponseSink,
                                                     final TCLockTimer waitTimer, final TimerCallback callback) {
    recordLockRequestStat(txn.getId().getNodeID(), txn.getId().getClientThreadID());

    if (!lockRequestTimeout.needsToWait()) {
      cannotAwardAndRespond(txn, lockLevel, lockResponseSink);
      return;
    }

    addPendingTryLockRequest(txn, lockLevel, lockRequestTimeout, lockResponseSink, waitTimer, callback);
  }

  private void addPendingTryLockRequest(final ServerThreadContext txn, final int lockLevel,
                                        final TimerSpec lockRequestTimeout, final Sink lockResponseSink,
                                        final TCLockTimer waitTimer, final TimerCallback callback) {
    Request request = addPending(txn, lockLevel, lockResponseSink, lockRequestTimeout, true);

    if (lockRequestTimeout.needsToWait()) {
      TryLockContextImpl tryLockWaitRequestContext = new TryLockContextImpl(txn, this, lockRequestTimeout, lockLevel,
                                                                            lockResponseSink);
      scheduleWaitForTryLock(callback, waitTimer, request, tryLockWaitRequestContext);
    }
  }

  private void addPendingLockRequest(final ServerThreadContext threadContext, final int lockLevel,
                                     final Sink awardLockSink) {
    addPending(threadContext, lockLevel, awardLockSink, null, false);
  }

  private Request addPending(final ServerThreadContext threadContext, final int lockLevel, final Sink awardLockSink,
                             final TimerSpec lockRequestTimeout, final boolean noBlock) {
    Assert.assertFalse(isNull());
    // debug("addPending() - BEGIN -", threadContext, ", ", LockLevel.toString(lockLevel));

    Request request = createRequest(threadContext, lockLevel, awardLockSink, lockRequestTimeout, noBlock);

    if (pendingLockRequests.containsValue(request)) {
      logger.debug("Ignoring existing Request " + request + " in Lock " + lockID);
      return request;
    }

    initPendingLockRequests().put(threadContext, request);
    return request;
  }

  private boolean isGreedyRequest(final ServerThreadContext txn) {
    return (txn.getId().getClientThreadID().equals(ThreadID.VM_ID));
  }

  private boolean isPolicyGreedy() {
    return lockPolicy == LockManagerImpl.GREEDY_LOCK_POLICY;
  }

  int getLockPolicy() {
    return lockPolicy;
  }

  int getLockLevel() {
    return level;
  }

  void setLockPolicy(final int newPolicy) {
    if (!isNull() && newPolicy != lockPolicy) {
      this.lockPolicy = newPolicy;
      if (!isPolicyGreedy()) {
        recall(LockLevel.WRITE);
      }
    }
  }

  private void awardGreedyAndRespond(final ServerThreadContext txn, final int requestedLockLevel,
                                     final Sink lockResponseSink) {
    // debug("awardGreedyAndRespond() - BEGIN - ", txn, ",", LockLevel.toString(requestedLockLevel));
    final ServerThreadContext clientTx = getClientVMContext(txn);
    final int greedyLevel = LockLevel.makeGreedy(requestedLockLevel);

    NodeID ch = txn.getId().getNodeID();
    checkAndClearStateOnGreedyAward(txn.getId().getClientThreadID(), ch, requestedLockLevel);
    Holder holder = awardAndRespond(clientTx, txn.getId().getClientThreadID(), greedyLevel, lockResponseSink);
    holder.setSink(lockResponseSink);
    greedyHolders.put(ch, holder);
  }

  private void cannotAwardAndRespond(final ServerThreadContext txn, final int requestedLockLevel,
                                     final Sink lockResponseSink) {
    lockResponseSink.add(createLockRejectedResponseContext(this.lockID, txn.getId(), requestedLockLevel));
    recordLockRejectStat(txn.getId().getNodeID(), txn.getId().getClientThreadID());
  }

  private Holder awardAndRespond(final ServerThreadContext txn, final ThreadID requestThreadID,
                                 final int requestedLockLevel, final Sink lockResponseSink) {
    // debug("awardRespond() - BEGIN - ", txn, ",", LockLevel.toString(requestedLockLevel));
    Holder holder = awardLock(txn, requestThreadID, requestedLockLevel);
    lockResponseSink.add(createLockAwardResponseContext(this.lockID, txn.getId(), requestedLockLevel));
    return holder;
  }

  private void recallIfPending(final int recallLevel) {
    if (pendingLockRequests.size() > 0) {
      recall(recallLevel);
    }
  }

  private void recall(final int recallLevel) {
    if (recalled) { return; }
    recordLockHoppedStat();
    for (Holder holder : greedyHolders.values()) {
      holder.getSink().add(
                           createLockRecallResponseContext(holder.getLockID(), holder.getThreadContext().getId(),
                                                           recallLevel));
      recalled = true;
    }
  }

  synchronized void notify(final ServerThreadContext txn, final boolean all, final NotifiedWaiters addNotifiedWaitersTo)
      throws TCIllegalMonitorStateException {
    // debug("notify() - BEGIN - ", txn, ", all = " + all);
    if (waiters.containsKey(txn)) { throw Assert.failure("Can't notify self: " + txn); }
    checkLegalWaitNotifyState(txn);

    if (waiters.size() > 0) {
      final int numToNotify = all ? waiters.size() : 1;
      for (int i = 0; i < numToNotify; i++) {
        LockWaitContext wait = (LockWaitContext) removeFirstValue(waiters);
        removeAndCancelWaitTimer(wait);
        recordLockRequestStat(wait.getNodeID(), wait.getThreadID());
        createPendingFromWaiter(wait);
        addNotifiedWaitersTo.addNotification(new LockContext(lockID, wait.getNodeID(), wait.getThreadID(), wait
            .lockLevel(), lockType));
      }
    }
  }

  private static Object removeFirstValue(Map map) {
    if (map == EMPTY_MAP) { return null; }

    // Make sure we're dealing with a LinkedHashMap here since this method assumes an ordered map
    LinkedHashMap lhm = (LinkedHashMap) map;

    Iterator iter = lhm.values().iterator();
    if (iter.hasNext()) {
      Object removed = iter.next();
      iter.remove();
      return removed;
    }

    return null;
  }

  synchronized void interrupt(final ServerThreadContext txn) {
    if (waiters.size() == 0 || !waiters.containsKey(txn)) {
      logger.warn("Cannot interrupt: " + txn + " is not waiting.");
      return;
    }
    LockWaitContext wait = waiters.remove(txn);
    recordLockRequestStat(wait.getNodeID(), wait.getThreadID());
    removeAndCancelWaitTimer(wait);
    createPendingFromWaiter(wait);
  }

  private void removeAndCancelWaitTimer(final LockWaitContext wait) {
    TimerTask task = timers.remove(wait);
    if (task != null) task.cancel();
  }

  private Request createPendingFromWaiter(final LockWaitContext wait) {
    // XXX: This cast to WaitContextImpl is lame. I'm not sure how to refactor it right now.
    Request request = createRequest(((LockWaitContextImpl) wait).getThreadContext(), wait.lockLevel(), wait
        .getLockResponseSink(), null, false);
    createPending(wait, request);
    return request;
  }

  private void createPending(final LockWaitContext wait, final Request request) {
    ServerThreadContext txn = ((LockWaitContextImpl) wait).getThreadContext();
    initPendingLockRequests().put(txn, request);

    if (isPolicyGreedy() && hasGreedyHolders()) {
      recall(request.getLockLevel());
    }
  }

  synchronized void tryRequestLockTimeout(final LockWaitContext context) {
    TryLockContextImpl tryLockContext = (TryLockContextImpl) context;
    ServerThreadContext txn = tryLockContext.getThreadContext();
    Object removed = timers.remove(txn);
    if (removed != null) {
      pendingLockRequests.remove(txn);
      Sink lockResponseSink = context.getLockResponseSink();
      int lockLevel = context.lockLevel();
      cannotAwardAndRespond(txn, lockLevel, lockResponseSink);
    }
  }

  synchronized void waitTimeout(final LockWaitContext context) {

    // debug("waitTimeout() - BEGIN -", context);
    // XXX: This cast is gross, too.
    ServerThreadContext txn = ((LockWaitContextImpl) context).getThreadContext();
    Object removed = waiters.remove(txn);

    if (removed != null) {
      timers.remove(context);
      Sink lockResponseSink = context.getLockResponseSink();
      int lockLevel = context.lockLevel();

      // Add a wait Timeout message
      lockResponseSink.add(createLockWaitTimeoutResponseContext(this.lockID, txn.getId(), lockLevel));

      recordLockRequestStat(context.getNodeID(), context.getThreadID());
      if (holders.size() == 0) {
        if (isPolicyGreedy() && (getWaiterCount() == 0)) {
          awardGreedyAndRespond(txn, lockLevel, lockResponseSink);
        } else {
          awardAndRespond(txn, txn.getId().getClientThreadID(), lockLevel, lockResponseSink);
        }
      } else {
        createPendingFromWaiter(context);
      }
    }
  }

  synchronized void wait(final ServerThreadContext txn, final TCLockTimer waitTimer, final TimerSpec call,
                         final TimerCallback callback, final Sink lockResponseSink)
      throws TCIllegalMonitorStateException {
    // debug("wait() - BEGIN -", txn, ", ", call);
    if (waiters.containsKey(txn)) throw Assert.failure("Already in wait set: " + txn);
    checkLegalWaitNotifyState(txn);

    Holder current = getHolder(txn);
    Assert.assertNotNull(current);

    LockWaitContext waitContext = new LockWaitContextImpl(txn, this, call, current.getLockLevel(), lockResponseSink);
    initWaiters().put(txn, waitContext);

    scheduleWait(callback, waitTimer, waitContext);
    removeCurrentHold(txn);

    nextPending();
  }

  // This method reestablished Wait State and schedules wait timeouts too. There are cases where we may need to ignore a
  // wait, if we already know about it. Note that it could be either in waiting or pending state.
  synchronized void addRecalledWaiter(final ServerThreadContext txn, final TimerSpec call, final int lockLevel,
                                      final Sink lockResponseSink, final TCLockTimer waitTimer,
                                      final TimerCallback callback) {
    // debug("addRecalledWaiter() - BEGIN -", txn, ", ", call);

    LockWaitContext waitContext = new LockWaitContextImpl(txn, this, call, lockLevel, lockResponseSink);
    if (waiters.containsKey(txn)) {
      logger.debug("addRecalledWaiter(): Ignoring " + waitContext + " as it is already in waiters list.");
      return;
    }
    Request request = createRequest(txn, lockLevel, lockResponseSink, null, false);
    if (pendingLockRequests.containsValue(request)) {
      logger.debug("addRecalledWaiter(): Ignoring " + waitContext + " as it is already in pending list.");
      return;
    }
    initWaiters().put(txn, waitContext);
    scheduleWait(callback, waitTimer, waitContext);
  }

  // This method reestablished Wait State and does not schedules wait timeouts too. This is
  // called when LockManager is starting and wait timers are started when the lock Manager is started.
  synchronized void reestablishWait(final ServerThreadContext txn, final TimerSpec call, final int lockLevel,
                                    final Sink lockResponseSink) {
    LockWaitContext waitContext = new LockWaitContextImpl(txn, this, call, lockLevel, lockResponseSink);
    Object old = initWaiters().put(txn, waitContext);
    if (old != null) throw Assert.failure("Already in wait set: " + txn);
  }

  synchronized void reestablishLock(final ServerThreadContext threadContext, final int requestedLevel,
                                    final Sink lockResponseSink) {
    if ((LockLevel.isWrite(requestedLevel) && holders.size() != 0)
        || (LockLevel.isRead(requestedLevel) && LockLevel.isWrite(this.level))) { throw new AssertionError(
                                                                                                           "Lock "
                                                                                                               + this
                                                                                                               + " already held by other Holder. Can't grant to "
                                                                                                               + threadContext
                                                                                                               + LockLevel
                                                                                                                   .toString(requestedLevel));

    }
    if (waiters.get(threadContext) != null) { throw new AssertionError("Thread " + threadContext
                                                                       + "is already in Wait state for Lock " + this
                                                                       + ". Can't grant Lock Hold !"); }
    recordLockRequestStat(threadContext.getId().getNodeID(), threadContext.getId().getClientThreadID());
    if (isGreedyRequest(threadContext)) {
      int greedyLevel = LockLevel.makeGreedy(requestedLevel);
      NodeID nid = threadContext.getId().getNodeID();
      Holder holder = awardLock(threadContext, threadContext.getId().getClientThreadID(), greedyLevel);
      holder.setSink(lockResponseSink);
      greedyHolders.put(nid, holder);
    } else {
      awardLock(threadContext, threadContext.getId().getClientThreadID(), requestedLevel);
    }
  }

  private void scheduleWait(final TimerCallback callback, final TCLockTimer waitTimer, final LockWaitContext waitContext) {
    final TimerTask timer = waitTimer.scheduleTimer(callback, waitContext.getTimerSpec(), waitContext);
    if (timer != null) {
      initTimers().put(waitContext, timer);
    }
  }

  private TimerTask scheduleWaitForTryLock(final TimerCallback callback, final TCLockTimer waitTimer,
                                           final Request pendingRequest,
                                           final TryLockContextImpl tryLockWaitRequestContext) {
    final TimerTask timer = waitTimer.scheduleTimer(callback, tryLockWaitRequestContext.getTimerSpec(),
                                                    tryLockWaitRequestContext);
    if (timer != null) {
      initTimers().put(tryLockWaitRequestContext.getThreadContext(), timer);
    }
    return timer;
  }

  private Map<TimerKey, TimerTask> initTimers() {
    if (timers == EMPTY_MAP) {
      timers = new HashMap<TimerKey, TimerTask>(MAP_SIZE, LOAD_FACTOR);
    }
    return timers;
  }

  private Map<ServerThreadContext, Request> initPendingLockRequests() {
    if (pendingLockRequests == EMPTY_MAP) {
      pendingLockRequests = new LinkedHashMap<ServerThreadContext, Request>(MAP_SIZE, LOAD_FACTOR);
    }
    return pendingLockRequests;
  }

  private Map<ServerThreadContext, LockWaitContext> initWaiters() {
    if (waiters == EMPTY_MAP) {
      waiters = new LinkedHashMap<ServerThreadContext, LockWaitContext>(MAP_SIZE, LOAD_FACTOR);
    }
    return waiters;
  }

  private void checkLegalWaitNotifyState(final ServerThreadContext threadContext) throws TCIllegalMonitorStateException {
    Assert.assertFalse(isNull());

    final int holdersSize = holders.size();
    if (holdersSize != 1) { throw new TCIllegalMonitorStateException("Invalid holder set size: " + holdersSize); }

    final int currentLevel = this.level;
    if (!LockLevel.isWrite(currentLevel)) { throw new TCIllegalMonitorStateException("Incorrect lock level: "
                                                                                     + LockLevel.toString(currentLevel)); }

    Holder holder = getHolder(threadContext);
    if (holder == null) {
      holder = getHolder(getClientVMContext(threadContext));
    }

    if (holder == null) {
      // make formatter sane
      throw new TCIllegalMonitorStateException(threadContext + " is not the current lock holder for: " + threadContext);
    }
  }

  private ServerThreadContext getClientVMContext(final ServerThreadContext threadContext) {
    return threadContextFactory.getOrCreate(threadContext.getId().getNodeID(), ThreadID.VM_ID);
  }

  public synchronized int getHoldersCount() {
    return holders.size();
  }

  public synchronized int getPendingCount() {
    return pendingLockRequests.size();
  }

  Collection getHoldersCollection() {
    return Collections.unmodifiableCollection(this.holders.values());
  }

  @Override
  public synchronized String toString() {
    try {
      StringBuffer rv = new StringBuffer();

      rv.append(lockID).append(", ").append("Level: ").append(LockLevel.toString(this.level)).append("\r\n");

      rv.append("Holders (").append(holders.size()).append(")\r\n");
      for (Object element : holders.values()) {
        rv.append('\t').append(element.toString()).append("\r\n");
      }

      rv.append("Wait Set (").append(waiters.size()).append(")\r\n");
      for (Object element : waiters.values()) {
        rv.append('\t').append(element.toString()).append("\r\n");
      }

      rv.append("Pending lock requests (").append(pendingLockRequests.size()).append(")\r\n");
      for (Object element : pendingLockRequests.values()) {
        rv.append('\t').append(element.toString()).append("\r\n");
      }

      return rv.toString();
    } catch (Throwable t) {
      t.printStackTrace();
      return "Exception in toString(): " + t.getMessage();
    }
  }

  private Holder awardLock(final ServerThreadContext threadContext, final ThreadID requestThreadID, final int lockLevel) {
    Assert.assertFalse(isNull());

    Holder holder = getHolder(threadContext);

    Assert.assertNull(holder);
    holder = new Holder(this.lockID, threadContext);
    holder.addLockLevel(lockLevel);
    Object prev = this.holders.put(threadContext, holder);
    Assert.assertNull(prev);
    this.level = holder.getLockLevel();
    recordLockAwardStat(holder.getNodeID(), requestThreadID, isGreedyRequest(threadContext), holder.getTimestamp());
    return holder;
  }

  public synchronized boolean isRead() {
    return LockLevel.READ == this.level;
  }

  public synchronized boolean isWrite() {
    return LockLevel.WRITE == this.level;
  }

  private boolean holdsReadLock(final ServerThreadContext threadContext) {
    Holder holder = getHolder(threadContext);
    if (holder != null) { return holder.getLockLevel() == LockLevel.READ; }
    return false;
  }

  private Holder getHolder(final ServerThreadContext threadContext) {
    return this.holders.get(threadContext);
  }

  public Holder getLockHolder(final ServerThreadContext threadContext) {
    Holder lockHolder = this.holders.get(threadContext);
    if (lockHolder == null) {
      lockHolder = this.holders.get(getClientVMContext(threadContext));
    }
    return lockHolder;
  }

  synchronized int getWaiterCount() {
    return this.waiters.size();
  }

  synchronized boolean hasPending() {
    return pendingLockRequests.size() > 0;
  }

  synchronized boolean hasWaiting() {
    return this.waiters.size() > 0;
  }

  boolean hasGreedyHolders() {
    return this.greedyHolders.size() > 0;
  }

  synchronized boolean hasWaiting(final ServerThreadContext threadContext) {
    return (this.waiters.get(threadContext) != null);
  }

  public LockID getLockID() {
    return lockID;
  }

  public boolean isNull() {
    return this.lockID.isNull();
  }

  @Override
  public int hashCode() {
    return this.lockID.hashCode();
  }

  @Override
  public boolean equals(final Object obj) {
    if (obj instanceof Lock) {
      Lock other = (Lock) obj;
      return this.lockID.equals(other.lockID);
    }
    return false;
  }

  private boolean readHolder() {
    // We only need to check the first holder as we cannot have 2 holder, one holding a READ and another holding a
    // WRITE.
    Holder holder = holders.values().iterator().next();
    return holder != null && LockLevel.isRead(holder.getLockLevel());
  }

  synchronized boolean nextPending() {
    Assert.eval(!isNull());
    // debug("nextPending() - BEGIN -");

    boolean clear;
    try {
      // Lock upgrade is not supported.
      // DEV-1999 : Note this can be called on node disconnects. If the lock state is in recalled, we shouldn't be
      // giving new request.
      if (!pendingLockRequests.isEmpty() && !recalled) {
        Request request = pendingLockRequests.values().iterator().next();
        int reqLockLevel = request.getLockLevel();

        boolean canGrantRequest = (reqLockLevel == LockLevel.READ) ? (holders.isEmpty() || readHolder()) : holders
            .isEmpty();
        if (canGrantRequest) {

          switch (reqLockLevel) {
            case LockLevel.WRITE: {
              removeFirstValue(pendingLockRequests);
              cancelTryLockTimer(request);
              // Give locks greedily only if there is no one waiting or pending for this lock
              if (isPolicyGreedy()) {
                if (getWaiterCount() == 0) {
                  boolean isAllPendingRequestsFromRequestNode = isAllPendingLockRequestsFromNode(request
                      .getRequesterID());
                  if (LOCK_LEASE_ENABLE || isAllPendingRequestsFromRequestNode) {
                    grantGreedyRequest(request);
                    if (LOCK_LEASE_ENABLE && !isAllPendingRequestsFromRequestNode) {
                      recallIfPending(LockLevel.WRITE);
                    }
                  } else {
                    grantRequest(request);
                  }
                } else {
                  // When there are other clients that are waiting on the lock, we do not grant the lock greedily
                  // because the client who
                  // own the greedy lock may do a notify and the local wait will get wake up. This may starve the wait
                  // in the other clients.
                  grantRequest(request);
                }
              } else {
                grantRequest(request);
              }
              break;
            }
            case LockLevel.READ: {
              // debug("nextPending() - granting READ request -", request);
              awardAllReads();
              break;
            }
            default: {
              throw new TCInternalError("Unknown lock level in request: " + reqLockLevel);
            }
          }
        }
      }
    } finally {
      clear = holders.size() == 0 && this.waiters.size() == 0 && this.pendingLockRequests.size() == 0;
    }

    return clear;
  }

  private Request cancelTryLockTimer(final Request request) {
    if (!(request instanceof TryLockRequest)) { return null; }

    ServerThreadContext requestThreadContext = request.getThreadContext();
    TimerTask recallTimer = timers.remove(requestThreadContext);
    if (recallTimer != null) {
      recallTimer.cancel();
      return request;
    }
    return null;
  }

  private void grantGreedyRequest(final Request request) {
    // debug("grantGreedyRequest() - BEGIN -", request);
    ServerThreadContext threadContext = request.getThreadContext();
    awardGreedyAndRespond(threadContext, request.getLockLevel(), request.getLockResponseSink());
  }

  private void grantRequest(final Request request) {
    // debug("grantRequest() - BEGIN -", request);
    ServerThreadContext threadContext = request.getThreadContext();
    awardLock(threadContext, threadContext.getId().getClientThreadID(), request.getLockLevel());
    request.execute(lockID);
  }

  /**
   * Remove the specified lock hold.
   * 
   * @return true if the current hold was an upgrade
   */
  synchronized boolean removeCurrentHold(final ServerThreadContext threadContext) {
    // debug("removeCurrentHold() - BEGIN -", threadContext);
    Holder holder = getHolder(threadContext);
    if (holder != null) {
      this.holders.remove(threadContext);
      if (isGreedyRequest(threadContext)) {
        removeGreedyHolder(threadContext.getId().getNodeID());
      }
      this.level = (holders.size() == 0 ? LockLevel.NIL_LOCK_LEVEL : LockLevel.READ);
      recordLockReleaseStat(holder.getNodeID(), holder.getThreadID());
    }
    return false;
  }

  synchronized boolean recallCommit(final ServerThreadContext threadContext) {
    // debug("recallCommit() - BEGIN -", threadContext);
    Assert.assertTrue(isGreedyRequest(threadContext));
    boolean issueRecall = !recalled && hasPending();
    removeCurrentHold(threadContext);
    if (issueRecall) {
      recall(LockLevel.WRITE);
    }
    if (recalled == false) { return nextPending(); }
    return false;
  }

  private synchronized void removeGreedyHolder(final NodeID nodeID) {
    // debug("removeGreedyHolder() - BEGIN -", channelID);
    greedyHolders.remove(nodeID);
    if (!hasGreedyHolders()) {
      recalled = false;
    }
  }

  synchronized void awardAllReads() {
    // debug("awardAllReads() - BEGIN -");
    List pendingReadLockRequests = new ArrayList(pendingLockRequests.size());
    boolean hasPendingWrites = false;

    for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext();) {
      Request request = (Request) i.next();
      if (request.getLockLevel() == LockLevel.READ) {
        pendingReadLockRequests.add(request);
        i.remove();
      } else if (!hasPendingWrites && request.getLockLevel() == LockLevel.WRITE) {
        hasPendingWrites = true;
      }
    }

    for (Iterator i = pendingReadLockRequests.iterator(); i.hasNext();) {
      Request request = (Request) i.next();
      cancelTryLockTimer(request);
      if (isPolicyGreedy()) {
        ServerThreadContext tid = request.getThreadContext();
        if (!holdsGreedyLock(tid)) {
          if (LOCK_LEASE_ENABLE || !hasPendingWrites) {
            grantGreedyRequest(request);
          } else {
            grantRequest(request);
          }
        }
      } else {
        grantRequest(request);
      }
    }
    if (LOCK_LEASE_ENABLE && hasPendingWrites) {
      recall(LockLevel.WRITE);
    }
  }

  synchronized boolean holdsSomeLock(final NodeID nodeID) {
    for (Holder holder : holders.values()) {
      if (holder.getNodeID().equals(nodeID)) { return true; }
    }
    return false;
  }

  synchronized boolean holdsGreedyLock(final ServerThreadContext threadContext) {
    return (greedyHolders.get(threadContext.getId().getNodeID()) != null);
  }

  synchronized boolean canAwardGreedilyOnTheClient(final ServerThreadContext threadContext, final int lockLevel) {
    Holder holder = greedyHolders.get(threadContext.getId().getNodeID());
    if (holder != null) { return (LockLevel.isWrite(holder.getLockLevel()) || holder.getLockLevel() == lockLevel); }
    return false;
  }

  void notifyStarted(final TimerCallback callback, final TCLockTimer timer) {
    for (LockWaitContext ctxt : waiters.values()) {
      scheduleWait(callback, timer, ctxt);
    }
  }

  synchronized boolean isAllPendingLockRequestsFromNode(final NodeID nodeID) {
    for (Request r : pendingLockRequests.values()) {
      if (!r.getRequesterID().equals(nodeID)) { return false; }
    }
    return true;
  }

  /**
   * This clears out stuff from the pending and wait lists that belonged to a dead session.
   * 
   * @param nid
   */
  synchronized void clearStateForNode(final NodeID nid) {
    // debug("clearStateForChannel() - BEGIN -", channelId);
    for (Iterator i = holders.values().iterator(); i.hasNext();) {
      Holder holder = (Holder) i.next();
      if (holder.getNodeID().equals(nid)) {
        i.remove();
      }
    }
    for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext();) {
      Request r = (Request) i.next();
      if (r.getRequesterID().equals(nid)) {
        i.remove();
      }
    }

    for (Iterator i = waiters.values().iterator(); i.hasNext();) {
      LockWaitContext wc = (LockWaitContext) i.next();
      if (wc.getNodeID().equals(nid)) {
        i.remove();
      }
    }

    for (Iterator i = timers.keySet().iterator(); i.hasNext();) {
      LockWaitContext wc = (LockWaitContext) i.next();
      if (wc.getNodeID().equals(nid)) {
        try {
          TimerTask task = timers.get(wc);
          task.cancel();
        } finally {
          i.remove();
        }
      }
    }
    removeGreedyHolder(nid);
  }

  synchronized void checkAndClearStateOnGreedyAward(final ThreadID clientThreadID, final NodeID nodeID,
                                                    final int requestedLevel) {
    // We dont want to award a greedy lock if there are waiters. Lock upgrade is not a problem as it is no longer
    // supported.
    Assert.assertTrue((requestedLevel == LockLevel.READ) || (waiters.size() == 0));

    for (Iterator i = holders.values().iterator(); i.hasNext();) {
      Holder holder = (Holder) i.next();
      if (holder.getNodeID().equals(nodeID)) {
        i.remove();
      }
    }
    for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext();) {
      Request r = (Request) i.next();
      if (r.getRequesterID().equals(nodeID)) {
        // debug("checkAndClear... removing request = ", r);
        i.remove();
        cancelTryLockTimer(r);
      }
    }
  }

  private void recordLockRequestStat(final NodeID nodeID, final ThreadID threadID) {
    lockStatsManager.recordLockRequested(lockID, nodeID, threadID, lockType, pendingLockRequests.size());
  }

  private void recordLockAwardStat(final NodeID nodeID, final ThreadID threadID, final boolean isGreedyRequest,
                                   final long awardTimestamp) {
    lockStatsManager.recordLockAwarded(lockID, nodeID, threadID, isGreedyRequest, awardTimestamp);
  }

  private void recordLockReleaseStat(final NodeID nodeID, final ThreadID threadID) {
    lockStatsManager.recordLockReleased(lockID, nodeID, threadID);
  }

  private void recordLockHoppedStat() {
    lockStatsManager.recordLockHopRequested(lockID);
  }

  private void recordLockRejectStat(final NodeID nodeID, final ThreadID threadID) {
    lockStatsManager.recordLockRejected(lockID, nodeID, threadID);
  }

  // private void debug(Object... objs) {
  // StringBuilder builder = new StringBuilder();
  // builder.append(lockID).append(" ");
  // for (Object obj : objs) {
  // builder.append(String.valueOf(obj)).append(" ");
  // }
  // logger.warn(builder.toString());
  // }

}
