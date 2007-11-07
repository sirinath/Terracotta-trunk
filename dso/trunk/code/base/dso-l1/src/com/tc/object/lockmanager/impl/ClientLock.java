/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.lockmanager.impl;

import com.tc.exception.TCLockUpgradeNotSupportedError;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.ClientLockStatManager;
import com.tc.object.lockmanager.api.LockFlushCallback;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.LockLevel;
import com.tc.object.lockmanager.api.LockNotPendingError;
import com.tc.object.lockmanager.api.LockRequest;
import com.tc.object.lockmanager.api.Notify;
import com.tc.object.lockmanager.api.RemoteLockManager;
import com.tc.object.lockmanager.api.ThreadID;
import com.tc.object.lockmanager.api.TryLockRequest;
import com.tc.object.lockmanager.api.WaitListener;
import com.tc.object.lockmanager.api.WaitLockRequest;
import com.tc.object.lockmanager.api.WaitTimer;
import com.tc.object.lockmanager.api.WaitTimerCallback;
import com.tc.object.tx.WaitInvocation;
import com.tc.util.Assert;
import com.tc.util.State;
import com.tc.util.TCAssertionError;
import com.tc.util.Util;

import gnu.trove.TIntIntHashMap;
import gnu.trove.TIntStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.Map.Entry;

class ClientLock implements WaitTimerCallback, LockFlushCallback {

  private static final TCLogger       logger                   = TCLogging.getLogger(ClientLock.class);

  private static final State          RUNNING                  = new State("RUNNING");
  private static final State          PAUSED                   = new State("PAUSED");

  private final Map                   holders                  = Collections.synchronizedMap(new HashMap());
  private final Set                   rejectedLockRequesterIDs = new HashSet();
  private final LockID                lockID;
  private final Map                   waitLocksByRequesterID   = new HashMap();
  private final Map                   pendingLockRequests      = new LinkedHashMap();
  private final Map                   waitTimers               = new HashMap();
  private final RemoteLockManager     remoteLockManager;
  private final WaitTimer             waitTimer;

  private final Greediness            greediness               = new Greediness();
  private int                         useCount                 = 0;
  private volatile State              state                    = RUNNING;
  private long                        timeUsed                 = System.currentTimeMillis();
  private final ClientLockStatManager lockStatManager;

  ClientLock(LockID lockID, RemoteLockManager remoteLockManager, WaitTimer waitTimer,
             ClientLockStatManager lockStatManager) {
    Assert.assertNotNull(lockID);
    this.lockID = lockID;
    this.remoteLockManager = remoteLockManager;
    this.waitTimer = waitTimer;
    this.lockStatManager = lockStatManager;
  }

  private void recordStatIfEnabled() {
    if (lockStatManager.isStatEnabled(lockID)) {
      lockStatManager.recordStackTrace(lockID);
    }
  }

  boolean tryLock(ThreadID threadID, WaitInvocation timeout, int type) {
    lock(threadID, type, timeout, true);
    return isHeldBy(threadID, type);
  }

  public void lock(ThreadID threadID, int type) {
    lock(threadID, type, null, false);
  }

  private void lock(ThreadID threadID, int type, WaitInvocation timeout, boolean noBlock) {
    int lockType = type;
    if (LockLevel.isSynchronous(type)) {
      if (!LockLevel.isSynchronousWrite(type)) { throw new AssertionError(
                                                                          "Only Synchronous WRITE lock is supported now"); }
      lockType = LockLevel.WRITE;
    }
    basicLock(threadID, lockType, timeout, noBlock);
    if (lockType != type) {
      awardSynchronous(threadID, lockType);
    }
  }

  private void basicLock(ThreadID requesterID, int type, WaitInvocation timeout, boolean noBlock) {
    final Object waitLock;
    final Action action = new Action();

    synchronized (this) {
      waitUntillRunning();

      recordStatIfEnabled();
      // if it is tryLock and is already being held by other thread of the same node, return
      // immediately.
      if (noBlock && isHeld() && !isHeldBy(requesterID) && !timeout.needsToWait()) { return; }
      // debug("lock - BEGIN - ", requesterID, LockLevel.toString(type));
      if (isHeldBy(requesterID)) {
        // deal with upgrades/downgrades on locks already held
        if (isConcurrentWriteLock(requesterID)) {
          // NOTE: when removing this restriction, there are other places to clean up:
          // 1) ClientTransactionManagerImpl.apply()
          // 2) DNAFactory.flushDNAFor(LockID)
          // 3) RemoteTransactionManagerImpl.commit()
          // 4) ClientLock.removeCurrent()
          throw new AssertionError("Don't currently support nested concurrent write locks");
        }

        if (LockLevel.isWrite(type) && isHoldingReadLockExclusively(requesterID)) {
          // do not allow lock upgrade
          throw new TCLockUpgradeNotSupportedError();
        }

        if (isHeldBy(requesterID, LockLevel.WRITE)) {
          // if we already hold a WRITE lock, allow this transaction to have any lock
          award(requesterID, type);
          return;
        }

        if (LockLevel.isRead(type) && isHeldBy(requesterID, LockLevel.READ)) {
          // if re-requesting a read lock, we don't need to ask the server
          award(requesterID, type);
          return;
        }
      }
      if (LockLevel.isConcurrent(type)) {
        award(requesterID, type);
        return;
      }

      if (canAwardGreedilyNow(requesterID, type)) {
        award(requesterID, type);
        return;
      }

      // All other cases have to wait for some reason or the other
      waitLock = addToPendingLockRequest(requesterID, type, timeout, noBlock);
      if (greediness.isNotGreedy()) {
        // debug("lock - remote requestLock ", requesterID, LockLevel.toString(type));
        if (noBlock) {
          remoteLockManager.tryRequestLock(lockID, requesterID, timeout, type);
        } else {
          remoteLockManager.requestLock(lockID, requesterID, type);
        }
      } else {
        // If the lock already granted to another thread greedily within the same JVM and if
        // it is a tryLock request with a timeout, schedule a local timer.
        if (noBlock && timeout.needsToWait()) {
          scheduleWaitForTryLock(requesterID, type, timeout);
        }

        if (isGreedyRecallNeeded(requesterID, type)) {
          // XXX::Greedy upgrades are not done for a reason.
          // debug("lock - calling RECALL ", requesterID, LockLevel.toString(type));
          greediness.recall(type);
        }
        if (canProceedWithRecall()) {
          greediness.startRecallCommit();
          action.addAction(Action.RECALL_COMMIT);
        }
      }
    }
    if (action.doRecallCommit()) {
      // debug("lock - calling RECALL Commit ", requesterID, LockLevel.toString(type));
      flush();
      recallCommit();
    }

    boolean isInterrupted = false;
    if (noBlock) {
      isInterrupted = waitForTryLock(requesterID, waitLock);
    } else {
      isInterrupted = waitForLock(requesterID, type, waitLock);
    }
    Util.selfInterruptIfNeeded(isInterrupted);
    // debug("lock - GOT IT - ", requesterID, LockLevel.toString(type));
  }

  /*
   * @returns true if the greedy lock should be let go.
   */
  private synchronized boolean isGreedyRecallNeeded(ThreadID threadID, int level) {
    if (greediness.isGreedy()) {
      // We let the lock recalled if the request is for WRITE and we hold a Greedy READ
      if (LockLevel.isWrite(level) && greediness.isReadOnly()) { return true; }
    }
    return false;
  }

  public void unlock(ThreadID threadID) {
    Action action;
    boolean changed;

    do {
      changed = false;
      // Examine
      synchronized (this) {
        waitUntillRunning();
        // debug("unlock - BEGIN - ", id);
        recordStatIfEnabled();
        action = unlockAction(threadID);
      }

      // Flush
      if (action.doRemoteLockRequest() || action.doRecallCommit() || action.doSynchronousCommit()) {
        // debug("unlock - flush - ", id);
        flush();
      }

      // modify and send
      synchronized (this) {
        // check to see if the lock state has changed in anyway
        Action newAction = unlockAction(threadID);
        if (action.equals(newAction)) {
          removeCurrent(threadID);
          if (action.doAwardGreedyLocks()) {
            awardLocksGreedily();
          } else if (action.doRecallCommit()) {
            greediness.startRecallCommit();
            // debug("unlock - calling RECALL Commit ", id);
            recallCommit();
          } else if (action.doRemoteLockRequest()) {
            remoteLockManager.releaseLock(lockID, threadID);
          }
        } else {
          // try again
          changed = true;
          logger.debug(lockID + " :: unlock() : " + threadID + " STATE CHANGED - From = " + action + " To = "
                       + newAction + " - retrying ...");
        }
      }
    } while (changed);
  }

  private Action unlockAction(ThreadID threadID) {
    final Action action = new Action();
    boolean remote = isRemoteUnlockRequired(threadID);
    if (greediness.isNotGreedy() && remote) {
      action.addAction(Action.REMOTE_LOCK_REQUEST);
    } else if (remote && canProceedWithRecall(threadID)) {
      // This is the last outstanding unlock, so sync with server.
      action.addAction(Action.RECALL_COMMIT);
    } else if (greediness.isGreedy()) {
      action.addAction(Action.AWARD_GREEDY_LOCKS);
    }
    if (isLockSynchronouslyHeld(threadID)) {
      action.addAction(Action.SYNCHRONOUS_COMMIT);
    }
    return action;
  }

  public void wait(ThreadID threadID, WaitInvocation call, Object waitLock, WaitListener listener)
      throws InterruptedException {
    Action action;
    boolean changed;
    int server_level = LockLevel.NIL_LOCK_LEVEL;
    if (listener == null) { throw new AssertionError("Null WaitListener passed."); }

    do {
      changed = false;

      // Examine
      synchronized (this) {
        waitUntillRunning();
        recordStatIfEnabled();
        checkValidWaitNotifyState(threadID);
        action = waitAction(threadID);
      }

      // Flush
      if (action.doRemoteLockRequest() || action.doRecallCommit() || action.doSynchronousCommit()) {
        flush();
      }

      // modify and send
      synchronized (this) {
        // check to see if the lock state has changed in anyway
        Action newAction = waitAction(threadID);
        if (action.equals(newAction)) {
          LockHold holder = (LockHold) this.holders.get(threadID);
          Assert.assertNotNull(holder);
          server_level = holder.goToWaitState();

          Object prev = waitLocksByRequesterID.put(threadID, waitLock);
          Assert.eval(prev == null);

          WaitLockRequest waitLockRequest = new WaitLockRequest(lockID, threadID, server_level, call);

          if (this.pendingLockRequests.put(threadID, waitLockRequest) != null) {
            // formatting
            throw new AssertionError("WaitLockRequest already pending: " + waitLockRequest);
          }

          if (action.doAwardGreedyLocks()) {
            scheduleWaitTimeout(waitLockRequest);
            awardLocksGreedily();
          } else if (action.doRecallCommit()) {
            greediness.startRecallCommit();
            recallCommit();
          } else if (action.doRemoteLockRequest()) {
            remoteLockManager.releaseLockWait(lockID, threadID, call);
          }
        } else {
          // try again - this could potentially loop forever in a highly contended environment
          changed = true;
          logger.debug(lockID + " :: wait() : " + threadID + " : STATE CHANGED - From = " + action + " To = "
                       + newAction + " - retrying ...");
        }
      }
    } while (changed);

    listener.handleWaitEvent();
    if (waitForLock(threadID, server_level, waitLock)) { throw new InterruptedException(); }
  }

  private Action waitAction(ThreadID threadID) {
    final Action action = new Action();
    if (greediness.isNotGreedy()) {
      action.addAction(Action.REMOTE_LOCK_REQUEST);
    } else if (canProceedWithRecall(threadID)) {
      action.addAction(Action.RECALL_COMMIT);
    } else if (greediness.isGreedy()) {
      action.addAction(Action.AWARD_GREEDY_LOCKS);
    }
    if (isLockSynchronouslyHeld(threadID)) {
      action.addAction(Action.SYNCHRONOUS_COMMIT);
    }
    return action;
  }

  public synchronized Notify notify(ThreadID threadID, boolean all) {
    boolean isRemote;
    waitUntillRunning();
    recordStatIfEnabled();

    checkValidWaitNotifyState(threadID);
    if (!greediness.isNotGreedy()) {
      isRemote = notifyLocalWaits(threadID, all);
    } else {
      isRemote = true;
    }
    return isRemote ? new Notify(lockID, threadID, all) : Notify.NULL;
  }

  private synchronized void handleInterruptIfWait(ThreadID threadID) {
    LockRequest lockRequest = (LockRequest) pendingLockRequests.get(threadID);
    if (!isOnlyWaitLockRequest(lockRequest)) { return; }
    movedToPending(threadID);
    if (canAwardGreedilyNow(threadID, lockRequest.lockLevel())) {
      awardLock(threadID, lockRequest.lockLevel());
      return;
    }
    if (greediness.isNotGreedy()) {
      // If the lock is not greedily awarded, we need to notify the server to move
      // the lock to the pending state.
      this.remoteLockManager.interrruptWait(lockID, threadID);
    }
  }

  // This method needs to be called from a synchronized(this) context.
  private void movedToPending(ThreadID threadID) {
    LockHold holder = (LockHold) this.holders.get(threadID);
    Assert.assertNotNull(holder);
    int server_level = holder.goToPending();
    LockRequest pending = new LockRequest(lockID, threadID, server_level);
    LockRequest waiter = (LockRequest) this.pendingLockRequests.remove(threadID);
    if (waiter == null) {
      logger.warn("Pending request " + pending + " is not present");
      return;
    }
    // if (waiter instanceof WaitLockRequest) {
    if (isOnlyWaitLockRequest(waiter)) {
      cancelTimer((WaitLockRequest) waiter);
    } else {
      logger.warn("Pending request " + pending + " is not a waiter: " + waiter);
    }
    this.pendingLockRequests.put(threadID, pending);
  }

  /**
   * This method is called from a stage-thread and should never block
   */
  public synchronized void notified(ThreadID threadID) {
    movedToPending(threadID);
  }

  /**
   * XXX:: This method is called from a stage-thread and should never block. Server recalls READ or WRITE depending on
   * what triggered the recall.
   */
  public synchronized void recall(int interestedLevel, LockFlushCallback callback) {
    // debug("recall() - BEGIN - ", LockLevel.toString(interestedLevel));
    if (greediness.isGreedy()) {
      greediness.recall(interestedLevel);
      if (canProceedWithRecall()) {
        greediness.startRecallCommit();
        if (isTransactionsForLockFlushed(callback)) {
          // debug("recall() - recall commit - ", LockLevel.toString(interestedLevel));
          recallCommit();
        }
      }
    }
  }

  /**
   * XXX:: This method is called from a stage-thread and should never block.
   */
  public void cannotAwardLock(ThreadID threadID, int level) {
    final Object waitLock;
    synchronized (this) {
      waitLock = waitLocksByRequesterID.remove(threadID);
      if (waitLock == null && !threadID.equals(ThreadID.VM_ID)) {
        // Not waiting for this lock
        throw new LockNotPendingError("Attempt to reject a lock request that isn't pending: lockID: " + lockID
                                      + ", level: " + level + ", requesterID: " + threadID
                                      + ", waitLocksByRequesterID: " + waitLocksByRequesterID);
      }
      LockRequest lockRequest = (LockRequest) pendingLockRequests.remove(threadID);
      if (lockRequest == null) {
        // formatting
        throw new AssertionError("Attempt to remove a pending lock request that wasn't pending; lockID: " + lockID
                                 + ", level: " + level + ", requesterID: " + threadID);
      }
      cancelTryLockWaitTimerIfNeeded(lockRequest);
    }
    synchronized (waitLock) {
      reject(threadID);
      waitLock.notifyAll();
    }
  }

  private void reject(ThreadID threadID) {
    synchronized (rejectedLockRequesterIDs) {
      rejectedLockRequesterIDs.add(threadID);
    }
  }

  /**
   * XXX:: This method is called from a stage-thread and should never block.
   */
  public void awardLock(ThreadID threadID, int level) {
    final Object waitLock;
    synchronized (this) {
      // debug("awardLock() - BEGIN - ", requesterID, LockLevel.toString(level));
      waitLock = waitLocksByRequesterID.remove(threadID);
      if (waitLock == null && !threadID.equals(ThreadID.VM_ID)) {
        // Not waiting for this lock
        throw new LockNotPendingError("Attempt to award a lock that isn't pending [lockID: " + lockID + ", level: "
                                      + level + ", requesterID: " + threadID + "]");
      }
      if (LockLevel.isGreedy(level)) {
        Assert.assertEquals(threadID, ThreadID.VM_ID);
        // A Greedy lock is issued by the server. From now on client handles all lock awards until
        // 1) a recall is issued
        // 2) an lock upgrade is needed.
        final int nlevel = LockLevel.makeNotGreedy(level);
        greediness.add(nlevel);
        awardLocksGreedily();
        return;
      }

      // LockRequest lockRequestKey = new LockRequest(id, requesterID, level);
      LockRequest lockRequest = (LockRequest) pendingLockRequests.remove(threadID);
      if (lockRequest == null) {
        // formatting
        throw new AssertionError("Attempt to remove a pending lock request that wasn't pending; lockID: " + lockID
                                 + ", level: " + level + ", requesterID: " + threadID);
      }
      cancelTryLockWaitTimerIfNeeded(lockRequest);
    }

    synchronized (waitLock) {
      award(threadID, level);
      waitLock.notifyAll();
    }
  }

  /*
   * @returns the wait object for lock request
   */
  private synchronized Object addToPendingLockRequest(ThreadID threadID, int lockLevel, WaitInvocation timeout,
                                                      boolean noBlock) {
    // Add Lock Request
    LockRequest lockRequest = null;
    if (noBlock) {
      lockRequest = new TryLockRequest(lockID, threadID, lockLevel, timeout);
    } else {
      lockRequest = new LockRequest(lockID, threadID, lockLevel);
    }
    Object old = pendingLockRequests.put(threadID, lockRequest);
    if (old != null) {
      // formatting
      throw new AssertionError("Lock request already outstandind - " + old);
    }

    // Add wait object for lock request
    Object o = new Object();
    Object prev = waitLocksByRequesterID.put(threadID, o);
    if (prev != null) { throw new AssertionError("Assert Failed : Previous value is not null. Prev = " + prev
                                                 + " Thread id = " + threadID); }
    return o;
  }

  private boolean waitForTryLock(ThreadID threadID, Object waitLock) {
    // debug("waitForTryLock() - BEGIN - ", requesterID, LockLevel.toString(type));
    boolean isInterrupted = false;
    synchronized (waitLock) {
      // We need to check if the respond has returned already before we do a wait
      while (!isLockRequestResponded(threadID)) {
        try {
          waitLock.wait();
        } catch (InterruptedException ioe) {
          isInterrupted = true;
        }
      }
    }
    return isInterrupted;
  }

  private void scheduleTryLockTimerIfNeeded(TryLockRequest request) {
    if (waitTimers.containsKey(request)) { return; }

    ThreadID threadID = request.threadID();
    int lockLevel = request.lockLevel();
    WaitInvocation timeout = request.getWaitInvocation();
    timeout.adjust();
    scheduleWaitForTryLock(threadID, lockLevel, timeout);
  }

  private void scheduleWaitForTryLock(ThreadID threadID, int lockLevel, WaitInvocation timeout) {
    TryLockRequest tryLockWaitRequest = (TryLockRequest) pendingLockRequests.get(threadID);
    scheduleWaitTimeout(tryLockWaitRequest);
  }

  private boolean isLockRequestResponded(ThreadID threadID) {
    if (isHeldBy(threadID)) { return true; }
    synchronized (rejectedLockRequesterIDs) {
      return rejectedLockRequesterIDs.remove(threadID);
    }
  }

  private boolean waitForLock(ThreadID threadID, int type, Object waitLock) {
    // debug("waitForLock() - BEGIN - ", requesterID, LockLevel.toString(type));
    boolean isInterrupted = false;
    while (!isHeldBy(threadID, type)) {
      try {
        synchronized (waitLock) {
          if (!isHeldBy(threadID, type)) {
            waitLock.wait();
          }
        }
      } catch (InterruptedException ioe) {
        if (!isInterrupted) {
          isInterrupted = true;
          handleInterruptIfWait(threadID);
        }
      } catch (Throwable e) {
        throw new TCRuntimeException(e);
      }
    }
    return isInterrupted;
    // debug("waitForLock() - WAKEUP - ", requesterID, LockLevel.toString(type));
  }

  // Schedule a timer.
  private synchronized void scheduleWaitTimeout(WaitLockRequest waitLockRequest) {
    final TimerTask timer = waitTimer.scheduleTimer(this, waitLockRequest.getWaitInvocation(), waitLockRequest);
    if (timer != null) {
      waitTimers.put(waitLockRequest, timer);
    }
  }

  private synchronized void awardLocksGreedily() {
    // debug("awardLocksGreedily() - BEGIN - ", "");
    List copy = new ArrayList(pendingLockRequests.values());
    for (Iterator i = copy.iterator(); i.hasNext();) {
      Object o = i.next();
      if (isOnlyWaitLockRequest(o)) continue;
      LockRequest lr = (LockRequest) o;
      if (canAwardGreedilyNow(lr.threadID(), lr.lockLevel())) {
        awardLock(lr.threadID(), lr.lockLevel());
      } else if (isTryLockRequest(lr)) {
        scheduleTryLockTimerIfNeeded((TryLockRequest) lr);
      }
    }
  }

  /**
   * Returns true if a remote lock release is required. The difference between this and the real removeCurrent is that
   * this does not change state.
   */
  private synchronized boolean isRemoteUnlockRequired(ThreadID threadID) {
    // debug("isRemoteUnlockRequired() - BEGIN - ", id);
    LockHold holder = (LockHold) this.holders.get(threadID);
    Assert.assertNotNull(holder);

    if (LockLevel.isConcurrent(holder.getLevel())) { return false; }

    return holder.isRemoteUnlockRequired();
  }

  /**
   * Returns true if a remote lock release is required
   */
  private synchronized boolean removeCurrent(ThreadID threadID) {
    // debug("removeCurrrent() - BEGIN - ", id);
    LockHold holder = (LockHold) this.holders.get(threadID);
    Assert.assertNotNull(holder);

    if (LockLevel.isConcurrent(holder.getLevel())) {
      holder.removeCurrent();

      if (holder.getLevel() == LockLevel.NIL_LOCK_LEVEL) {
        this.holders.remove(threadID);
      }

      return false;
    }

    boolean rv = holder.removeCurrent();

    if (holder.getLevel() == LockLevel.NIL_LOCK_LEVEL) {
      this.holders.remove(threadID);
    }

    return rv;
  }

  private void checkValidWaitNotifyState(ThreadID threadID) {
    if (!isHeldBy(threadID, LockLevel.WRITE)) {
      // make the formatter happy
      throw new IllegalMonitorStateException("The current Thread (" + threadID + ") does not hold a WRITE lock for "
                                             + lockID);
    }
  }

  /**
   * Returns true if this notification should be send to the server for handling. This notification is not needed to be
   * sent to the server if all is false and we have notified 1 waiter locally.
   */
  private synchronized boolean notifyLocalWaits(ThreadID threadID, boolean all) {
    for (Iterator i = new HashSet(pendingLockRequests.values()).iterator(); i.hasNext();) {
      Object o = i.next();
      if (isOnlyWaitLockRequest(o)) {
        WaitLockRequest wlr = (WaitLockRequest) o;
        notified(wlr.threadID());
        if (!all) { return false; }
      }
    }
    return true;
  }

  private boolean isTransactionsForLockFlushed(LockFlushCallback callback) {
    return remoteLockManager.isTransactionsForLockFlushed(lockID, callback);
  }

  public void transactionsForLockFlushed(LockID id) {
    Assert.assertEquals(lockID, id);
    recallCommit();
  }

  private synchronized void recallCommit() {
    // debug("recallCommit() - BEGIN - ", "");
    if (greediness.isRecallInProgress()) {
      greediness.recallComplete();
      cancelTimers();
      // Attach the pending lock requests and tryLock requests to the recall commit message.
      remoteLockManager.recallCommit(lockID, addHoldersToAsLockRequests(new ArrayList()),
                                     addAllWaitersTo(new ArrayList()), addAllPendingLockRequestsTo(new ArrayList()),
                                     addAllPendingTryLockRequestsTo(new ArrayList()));
    } else {
      logger.debug(lockID + " : recallCommit() : skipping as the state is not RECALL_IN_PROGRESS !");
    }
  }

  private void flush() {
    remoteLockManager.flush(lockID);
  }

  public synchronized Collection addAllWaitersTo(Collection c) {
    if (greediness.isNotGreedy()) {
      for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext();) {
        Object o = i.next();
        if (isOnlyWaitLockRequest(o)) {
          c.add(o);
        }
      }
    }
    return c;
  }

  public synchronized Collection addHoldersToAsLockRequests(Collection c) {
    if (greediness.isNotGreedy()) {
      for (Iterator i = holders.keySet().iterator(); i.hasNext();) {
        ThreadID threadID = (ThreadID) i.next();
        LockHold hold = (LockHold) holders.get(threadID);
        if (hold.isHolding() && hold.getServerLevel() != LockLevel.NIL_LOCK_LEVEL) {
          c.add(new LockRequest(this.lockID, threadID, hold.getServerLevel()));
        }
      }
    } else {
      // All other states -- GREEDY, RECALLED, RECALL-COMMIT-INPROGRESS
      c.add(new LockRequest(this.lockID, ThreadID.VM_ID, greediness.getLevel()));
    }
    return c;
  }

  public synchronized Collection addAllPendingLockRequestsTo(Collection c) {
    if (greediness.isNotGreedy()) {
      for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext();) {
        LockRequest request = (LockRequest) i.next();
        if (isWaitLockRequest(request)) continue;
        c.add(request);
      }
    }
    return c;
  }

  public synchronized Collection addAllPendingTryLockRequestsTo(Collection c) {
    if (greediness.isNotGreedy()) {
      for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext();) {
        LockRequest request = (LockRequest) i.next();
        if (isTryLockRequest(request)) {
          c.add(request);
        }
      }
    }
    return c;
  }

  public synchronized void incUseCount() {
    if (useCount == Integer.MAX_VALUE) { throw new AssertionError("Lock use count cannot exceed integer max value"); }
    useCount++;
    timeUsed = System.currentTimeMillis();
  }

  public synchronized void decUseCount() {
    if (useCount == 0) { throw new AssertionError("Lock use count is zero"); }
    useCount--;
    timeUsed = System.currentTimeMillis();
  }

  public synchronized int getUseCount() {
    return useCount;
  }

  // call from a synchronized(this) context
  private void cancelTryLockWaitTimerIfNeeded(LockRequest request) {
    if (isTryLockRequest(request)) {
      cancelTimer((TryLockRequest) request);
    }
  }

  private synchronized void cancelTimer(WaitLockRequest request) {
    TimerTask timer = (TimerTask) waitTimers.remove(request);
    if (timer != null) {
      timer.cancel();
    }
  }

  private synchronized void cancelTimers() {
    Collection copy = new ArrayList(waitTimers.keySet());
    for (Iterator iter = copy.iterator(); iter.hasNext();) {
      WaitLockRequest wlr = (WaitLockRequest) iter.next();
      cancelTimer(wlr);
    }
  }

  /*
   * @see ClientLock.addRecalledHolders();
   */
  private synchronized boolean canProceedWithRecall() {
    return canProceedWithRecall(ThreadID.NULL_ID);
  }

  private synchronized boolean canProceedWithRecall(ThreadID threadID) {
    if (greediness.isRecalled()) {
      Map map = addRecalledHoldersTo(new HashMap());
      if (threadID != ThreadID.NULL_ID) {
        map.remove(threadID);
      }
      for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext() && map.size() != 0;) {
        Object o = i.next();
        if (isOnlyWaitLockRequest(o)) {
          // These are not in the map.
          continue;
        }
        if (o instanceof LockRequest) {
          LockRequest lr = (LockRequest) o;
          map.remove(lr.threadID());
        }
      }
      return (map.size() == 0);
    }
    return false;
  }

  private void award(ThreadID threadID, int level) {
    // debug("award() - BEGIN - ", id, LockLevel.toString(level));
    synchronized (this) {
      LockHold holder = (LockHold) this.holders.get(threadID);
      if (holder == null) {
        holders.put(threadID, new LockHold(this.lockID, level));
      } else if (holder.isHolding()) {
        holder.add(level);
      } else {
        // Lock is awarded after wait
        try {
          holder.goToHolding(level);
        } catch (TCAssertionError er) {
          logger.warn("Lock in wrong STATE for holder - (" + threadID + ", " + LockLevel.toString(level) + ") - "
                      + this);
          throw er;
        }
      }
    }
  }

  private synchronized void awardSynchronous(ThreadID threadID, int lockLevel) {
    LockHold holder = (LockHold) this.holders.get(threadID);
    if (holder != null && holder.isHolding() && ((holder.getLevel() & lockLevel) == lockLevel)) {
      holder.makeLastAwardSynchronous(lockLevel);
    }
  }

  /*
   * @returns true if the lock can be awarded greedily now.
   */
  private synchronized boolean canAwardGreedilyNow(ThreadID threadID, int level) {
    if (greediness.isGreedy()) {
      // We can award the lock greedily now if
      // 1) The request is for WRITE and we hold a Greedy WRITE and there are no holders.
      // 2) The request is for WRITE and we hold a Greedy WRITE and there is only 1 holder, which is the requesterID.
      // This is a valid local UPGRADE request.
      // 3) The request is for READ and we hold a Greedy WRITE and there are no holders of WRITE.
      // 4) The request is for READ and we hold a Greedy READ
      if (LockLevel.isWrite(level) && greediness.isWrite() && (!isHeld() || ((heldCount() == 1) && isHeldBy(threadID)))) {
        // (1) and (2)
        return true;
      } else if (LockLevel.isRead(level) && greediness.isWrite() && !isWriteHeld()) {
        // (3)
        return true;
      } else if (LockLevel.isRead(level) && greediness.isRead()) {
        // (4)
        return true;
      }
    }
    return false;
  }

  private boolean isLockSynchronouslyHeld(ThreadID threadID) {
    LockHold holder = (LockHold) this.holders.get(threadID);
    if (holder != null && holder.isHolding()) { return holder.isLastLockSynchronouslyHeld(); }
    return false;
  }

  /*
   * @returns true if the greedy lock should be let go.
   */
  // private synchronized boolean isGreedyRecallNeeded(ThreadID threadID, int level) {
  // if (greediness.isGreedy()) {
  // // We let the lock recalled if the request is for WRITE and we hold a Greedy READ
  // if (LockLevel.isWrite(level) && greediness.isReadOnly()) { return true; }
  // }
  // return false;
  // }
  private boolean isWriteHeld() {
    synchronized (holders) {
      for (Iterator it = holders.values().iterator(); it.hasNext();) {
        LockHold holder = (LockHold) it.next();
        if (holder.isHolding() && LockLevel.isWrite(holder.getLevel())) { return true; }
      }
      return false;
    }
  }

  /**
   * This method adds the ThreadIDs of the Holders of the locks that is been recalled. The server can recall a lock
   * because of a READ or a WRITE request. The Holders for whom we need to worry about is explained below. Greedy |
   * Recall | Who 1) R | W | No Holders who are not pending lock request/ wait 2) W | W | No Holders who are not pending
   * lock request/ wait 3) W | R | No Write Holders who are not pending lock request/ wait
   */
  private synchronized Map addRecalledHoldersTo(Map map) {
    Assert.assertTrue(greediness.isRecalled());
    for (Iterator it = holders.entrySet().iterator(); it.hasNext();) {
      Entry e = (Entry) it.next();
      ThreadID id = (ThreadID) e.getKey();
      LockHold holder = (LockHold) e.getValue();
      if (!holder.isHolding()) continue;
      if ((greediness.getRecalledLevel() == LockLevel.READ) && LockLevel.isRead(holder.getLevel())) {
        // (3)
        continue;
      }
      map.put(id, id);
    }
    return map;
  }

  public synchronized void waitTimeout(Object callbackObject) {
    waitUntillRunning();
    // debug("waitTimeout() - BEGIN - ", callbackObject);
    if (isTryLockRequest(callbackObject)) {
      // If the callbackObject is a tryLock request, reject the tryLock request.
      TryLockRequest wlr = (TryLockRequest) callbackObject;
      LockID timeoutLockID = wlr.lockID();
      if (!lockID.equals(timeoutLockID)) { throw new AssertionError("waitTimeout: LockIDs are not the same : " + lockID
                                                                    + " : " + timeoutLockID); }
      // We need to check if the tryLock is still waiting locally to accommodate the race condition that the
      // timer thread just timeouts and before the timer thread invokes this method, the application thread does
      // an unlock and recallCommit, which will move the tryLock request to the server.
      if (isTryLockWaiting(wlr)) {
        int timeoutLockLevel = wlr.lockLevel();
        ThreadID timeoutThreadID = wlr.threadID();
        cannotAwardLock(timeoutThreadID, timeoutLockLevel);
      }
    } else if (isOnlyWaitLockRequest(callbackObject)) {
      WaitLockRequest wlr = (WaitLockRequest) callbackObject;
      LockID timedoutLockID = wlr.lockID();
      if (!lockID.equals(timedoutLockID)) { throw new AssertionError("WaitTimeout: LockIDs are not the same : "
                                                                     + lockID + " : " + timedoutLockID); }
      if (greediness.isWrite() && isWaiting(wlr.threadID())) {
        notified(wlr.threadID());
        awardLocksGreedily();
        return;
      }
    }
    logger.warn("Ignoring wait timeout for : " + callbackObject);
  }

  public synchronized boolean isClear() {
    return (holders.isEmpty() && greediness.isNotGreedy() && (pendingLockRequests.size() == 0) && (useCount == 0));
  }

  // This method is synchronized such that we can quickly inspect for potential timeouts and only on possible
  // timeouts we grab the lock.
  public boolean timedout() {
    if (useCount != 0) { return false; }
    synchronized (this) {
      return (holders.isEmpty() && greediness.isGreedy() && (pendingLockRequests.size() == 0) && (useCount == 0) && ((System
          .currentTimeMillis() - timeUsed) > ClientLockManagerImpl.TIMEOUT));
    }
  }

  private boolean isHeldBy(ThreadID threadID) {
    synchronized (holders) {
      LockHold holder = (LockHold) holders.get(threadID);
      if (holder != null) { return holder.isHolding(); }
      return false;
    }
  }

  public boolean isHoldingReadLockExclusively(ThreadID threadID) {
    return isHeldBy(threadID, LockLevel.READ) && !isHeldBy(threadID, LockLevel.WRITE);
  }

  public boolean isHeldBy(ThreadID threadID, int level) {
    synchronized (holders) {
      LockHold holder = (LockHold) holders.get(threadID);
      if (holder != null) { return ((holder.isHolding()) && ((holder.getLevel() & level) == level)); }
      return false;
    }
  }

  public boolean isHeld() {
    synchronized (holders) {
      for (Iterator it = holders.values().iterator(); it.hasNext();) {
        LockHold holder = (LockHold) it.next();
        if (holder.isHolding()) { return true; }
      }
      return false;
    }
  }

  private boolean isTryLockWaiting(TryLockRequest request) {
    return waitTimers.containsKey(request);
  }

  private boolean isWaiting(ThreadID threadID) {
    synchronized (holders) {
      LockHold holder = (LockHold) holders.get(threadID);
      if (holder != null) { return holder.isWaiting(); }
      return false;
    }
  }

  private int heldCount() {
    int count = 0;
    synchronized (holders) {
      for (Iterator it = holders.values().iterator(); it.hasNext();) {
        LockHold holder = (LockHold) it.next();
        if (holder.isHolding()) {
          count++;
        }
      }
    }
    return count;
  }

  public int localHeldCount(ThreadID threadID, int lockLevel) {
    LockHold holder;
    synchronized (holders) {
      holder = (LockHold) holders.get(threadID);
    }
    if (holder == null) return 0;
    else return holder.heldCount(lockLevel);
  }

  public synchronized int queueLength() {
    int count = 0;
    for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext();) {
      Object o = i.next();
      if (!isOnlyWaitLockRequest(o)) count++;
    }
    return count;
  }

  public synchronized int waitLength() {
    int localCount = 0;
    for (Iterator i = pendingLockRequests.values().iterator(); i.hasNext();) {
      Object o = i.next();
      if (isOnlyWaitLockRequest(o)) localCount++;
    }
    return localCount;
  }

  public LockID getLockID() {
    return lockID;
  }

  public int hashCode() {
    return this.lockID.hashCode();
  }

  public boolean equals(Object obj) {
    if (obj instanceof ClientLock) {
      ClientLock lock = (ClientLock) obj;
      return lock.lockID.equals(lockID);
    }
    return false;
  }

  public String toString() {
    return "Lock@" + System.identityHashCode(this) + " [ " + lockID + " ] : Holders = " + holders
           + " : PendingLockRequest : " + pendingLockRequests + " : Use count : " + useCount + " : state : " + state
           + " : " + greediness;
  }

  private boolean isConcurrentWriteLock(ThreadID threadID) {
    LockHold holder = (LockHold) holders.get(threadID);
    if (holder != null) { return LockLevel.isConcurrent(holder.getLevel()); }
    return false;
  }

  public void pause() {
    state = PAUSED;
  }

  public synchronized void unpause() {
    state = RUNNING;
    notifyAll();
  }

  private synchronized void waitUntillRunning() {
    boolean isInterrupted = false;
    while (state != RUNNING) {
      try {
        wait();
      } catch (InterruptedException e) {
        isInterrupted = true;
      }
    }
    Util.selfInterruptIfNeeded(isInterrupted);
  }

  private boolean isWaitLockRequest(Object request) {
    return request instanceof WaitLockRequest;
  }

  private boolean isTryLockRequest(Object request) {
    return request instanceof TryLockRequest;
  }

  private boolean isOnlyWaitLockRequest(Object request) {
    return isWaitLockRequest(request) && !isTryLockRequest(request);
  }

  // I wish we were using 1.5 !!!
  // private void debug(Object o1, Object o2) {
  // logger.warn(lockID + String.valueOf(o1) + String.valueOf(o2));
  // }
  //
  // private void debug(Object o1, Object o2, Object o3) {
  // logger.warn(lockID + String.valueOf(o1) + String.valueOf(o2) + String.valueOf(o3));
  // }
  //
  // private void debug(Object o1, Object o2, Object o3, Object o4) {
  // logger.warn(lockID + String.valueOf(o1) + String.valueOf(o2) + String.valueOf(o3) + String.valueOf(o4));
  // }
  //
  // private void debug(Object o) {
  // logger.warn(lockID + String.valueOf(o));
  // }
  //

  private static class LockHold {
    private static final State   HOLDING = new State("HOLDING");
    private static final State   WAITING = new State("WAITING");
    private static final State   PENDING = new State("PENDING");

    private int                  level;
    private int                  server_level;
    private State                state;
    private final TIntIntHashMap counts  = new TIntIntHashMap();
    private final TIntStack      levels  = new TIntStack();
    private final LockID         lockID;

    LockHold(LockID lockID, int level) {
      this.lockID = lockID;
      if (!LockLevel.isDiscrete(level)) { throw new AssertionError("Non-discreet level " + level); }
      Assert.eval(level != LockLevel.NIL_LOCK_LEVEL);
      this.level = level;
      this.levels.push(level);
      this.counts.put(level, 1);
      initServerLevel();
      this.state = HOLDING;
    }

    private void initServerLevel() {
      if (level == LockLevel.READ || level == LockLevel.WRITE) {
        server_level = level;
      } else {
        server_level = LockLevel.NIL_LOCK_LEVEL;
      }
    }

    int getServerLevel() {
      return this.server_level;
    }

    int getLevel() {
      return this.level;
    }

    boolean isHolding() {
      return (state == HOLDING);
    }

    boolean isWaiting() {
      return (state == WAITING);
    }

    boolean isPending() {
      return (state == PENDING);
    }

    int heldCount() {
      return levels.size();
    }

    int heldCount(int lockLevel) {
      return this.counts.get(lockLevel);
    }

    void makeLastAwardSynchronous(int lockLevel) {
      int lastLevel = this.levels.pop();
      Assert.assertEquals(lockLevel, lastLevel);
      this.levels.push(LockLevel.makeSynchronous(lockLevel));
    }

    boolean isLastLockSynchronouslyHeld() {
      int lastLevel = this.levels.peek();
      return LockLevel.isSynchronous(lastLevel);
    }

    void add(int lockLevel) {
      Assert.eval("Non-discreet level " + lockLevel, LockLevel.isDiscrete(lockLevel));

      this.levels.push(lockLevel);
      this.level |= lockLevel;
      Assert.eval(level != LockLevel.NIL_LOCK_LEVEL);
      if ((lockLevel == LockLevel.READ && (!LockLevel.isWrite(server_level))) || (lockLevel == LockLevel.WRITE)) {
        server_level |= lockLevel;
      }
      if (!this.counts.increment(lockLevel)) {
        this.counts.put(lockLevel, 1);
      }
    }

    /**
     * Returns true if a remote lock release is required. This method does not change the state.
     */
    boolean isRemoteUnlockRequired() {
      Assert.eval(this.levels.size() > 0);
      int lastLevel = LockLevel.makeNotSynchronous(levels.peek());

      Assert.eval(this.counts.contains(lastLevel));
      int count = this.counts.get(lastLevel);
      Assert.eval(count > 0);

      count--;
      if (count > 0) { return false; }

      return lastLevel == LockLevel.WRITE || ((this.level ^ lastLevel) == LockLevel.NIL_LOCK_LEVEL);
    }

    /**
     * Returns true if a remote lock release is required
     */
    boolean removeCurrent() {
      Assert.eval(this.levels.size() > 0);
      int lastLevel = LockLevel.makeNotSynchronous(levels.pop());

      Assert.eval(this.counts.contains(lastLevel));
      int count = this.counts.remove(lastLevel);
      Assert.eval(count > 0);

      count--;
      if (count > 0) {
        this.counts.put(lastLevel, count);
        return false;
      }

      this.level ^= lastLevel;

      if ((lastLevel == LockLevel.READ && (!LockLevel.isWrite(server_level))) || (lastLevel == LockLevel.WRITE)) {
        server_level ^= lastLevel;
      }
      return lastLevel == LockLevel.WRITE || this.level == LockLevel.NIL_LOCK_LEVEL;
    }

    int goToWaitState() {
      Assert.assertTrue(LockLevel.isWrite(this.level));
      Assert.assertTrue(state == HOLDING);
      this.state = WAITING;
      /*
       * server_level is not changed to NIL_LOCK_LEVEL even though the server will release the lock as we need to know
       * what state we were holding before wait on certain scenarios like server crash etc.
       *
       * @see ClientLockManager.notified
       */
      return this.server_level;
    }

    int goToPending() {
      /*
       * The lock might not be in WAITING state if the server has restarted and the client has been notified again
       * because of a resent of an already applied transaction. The lock could even be in HOLDING state if the resent
       * transaction is already fully ACKED before the server crashed and the originating client processes the ACK after
       * resend, since notifies and lock awards happen in different thread. So we ignore such notifies.
       */
      if (this.state != WAITING) {
        logger.warn(this.lockID + ": Ignoring Moving to PENDING since not in WAITING state:  current state = "
                    + this.state);
      } else {
        this.state = PENDING;
      }
      return this.server_level;
    }

    void goToHolding(int slevel) {
      Assert.assertTrue(slevel == server_level);
      if (state != PENDING) throw new AssertionError("Attempt to to to HOLDING while not PENDING: " + state);
      this.state = HOLDING;
    }

    public String toString() {
      return "LockHold[" + state + "," + LockLevel.toString(level) + "]";
    }
  }

  private static class Greediness {
    private static final State NOT_GREEDY         = new State("NOT GREEDY");
    private static final State GREEDY             = new State("GREEDY");
    private static final State RECALLED           = new State("RECALLED");
    private static final State RECALL_IN_PROGRESS = new State("RECALL IN PROGRESS");

    private int                level              = LockLevel.NIL_LOCK_LEVEL;
    private int                recallLevel        = LockLevel.NIL_LOCK_LEVEL;
    private State              state              = NOT_GREEDY;

    void add(int l) {
      this.level |= l;
      state = GREEDY;
    }

    int getLevel() {
      return level;
    }

    int getRecalledLevel() {
      return recallLevel;
    }

    void recall(int rlevel) {
      Assert.assertTrue(state == GREEDY);
      this.recallLevel |= rlevel;
      state = RECALLED;
    }

    boolean isRead() {
      return LockLevel.isRead(level);
    }

    boolean isReadOnly() {
      return isRead() && !isWrite();
    }

    boolean isWrite() {
      return LockLevel.isWrite(level);
    }

    boolean isUpgrade() {
      return isRead() && isWrite();
    }

    boolean isGreedy() {
      return (state == GREEDY);
    }

    // XXX:: Note that isNotGreedy() != (!isGreedy())
    boolean isNotGreedy() {
      return (state == NOT_GREEDY);
    }

    public String toString() {
      return "Greedy Token [ Lock Level = " + LockLevel.toString(level) + ", Recall Level = "
             + LockLevel.toString(recallLevel) + ", " + state + "]";
    }

    boolean isRecalled() {
      return (state == RECALLED);
    }

    boolean isRecallInProgress() {
      return (state == RECALL_IN_PROGRESS);
    }

    void startRecallCommit() {
      Assert.assertTrue(state == RECALLED);
      state = RECALL_IN_PROGRESS;
    }

    void recallComplete() {
      Assert.assertTrue(state == RECALL_IN_PROGRESS);
      this.state = NOT_GREEDY;
      this.recallLevel = LockLevel.NIL_LOCK_LEVEL;
      this.level = LockLevel.NIL_LOCK_LEVEL;
    }
  }

  private static class Action {
    private static final int NIL_ACTION          = 0x00;
    private static final int REMOTE_LOCK_REQUEST = 0x01;
    private static final int RECALL              = 0x02;
    private static final int RECALL_COMMIT       = 0x04;
    private static final int AWARD_GREEDY_LOCKS  = 0x08;
    private static final int SYNCHRONOUS_COMMIT  = 0x10;

    private int              action              = NIL_ACTION;

    void addAction(int a) {
      action |= a;
    }

    boolean doRemoteLockRequest() {
      return ((action & REMOTE_LOCK_REQUEST) == REMOTE_LOCK_REQUEST);
    }

    boolean doRecall() {
      return ((action & RECALL) == RECALL);
    }

    boolean doRecallCommit() {
      return ((action & RECALL_COMMIT) == RECALL_COMMIT);
    }

    boolean doAwardGreedyLocks() {
      return ((action & AWARD_GREEDY_LOCKS) == AWARD_GREEDY_LOCKS);
    }

    boolean doSynchronousCommit() {
      return ((action & SYNCHRONOUS_COMMIT) == SYNCHRONOUS_COMMIT);
    }

    public boolean equals(Object o) {
      if (!(o instanceof Action)) return false;
      return (((Action) o).action == action);
    }

    public int hashCode() {
      return action;
    }

    public String toString() {
      return "Action:[" + getDescription() + "]";
    }

    public String getDescription() {
      if (action == NIL_ACTION) { return "NIL_ACTION"; }
      StringBuffer sb = new StringBuffer(" ");
      if (doAwardGreedyLocks()) sb.append("AWARD_GREEDY_LOCKS,");
      if (doRecall()) sb.append("RECALL,");
      if (doRecallCommit()) sb.append("RECALL_COMMIT,");
      if (doRemoteLockRequest()) sb.append("REMOTE_LOCK_REQUEST,");
      if (doSynchronousCommit()) sb.append("SYNCHRONOUS_COMMIT,");
      sb.setLength(sb.length() - 1);
      return sb.toString();
    }
  }
}
