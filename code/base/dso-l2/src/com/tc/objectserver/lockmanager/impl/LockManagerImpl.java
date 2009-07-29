/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.lockmanager.impl;

import com.tc.async.api.Sink;
import com.tc.exception.ImplementMe;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.L2LockStatsManager;
import com.tc.net.NodeID;
import com.tc.object.lockmanager.api.LockContext;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.LockLevel;
import com.tc.object.lockmanager.api.TCLockTimer;
import com.tc.object.lockmanager.api.ThreadID;
import com.tc.object.lockmanager.api.TimerCallback;
import com.tc.object.lockmanager.api.TryLockContext;
import com.tc.object.lockmanager.api.WaitContext;
import com.tc.object.lockmanager.impl.TCLockTimerImpl;
import com.tc.object.net.DSOChannelManager;
import com.tc.object.tx.TimerSpec;
import com.tc.objectserver.lockmanager.api.DeadlockChain;
import com.tc.objectserver.lockmanager.api.DeadlockResults;
import com.tc.objectserver.lockmanager.api.LockMBean;
import com.tc.objectserver.lockmanager.api.LockManager;
import com.tc.objectserver.lockmanager.api.LockManagerMBean;
import com.tc.objectserver.lockmanager.api.LockWaitContext;
import com.tc.objectserver.lockmanager.api.NotifiedWaiters;
import com.tc.objectserver.lockmanager.api.TCIllegalMonitorStateException;
import com.tc.text.PrettyPrinter;
import com.tc.text.PrettyPrinterImpl;
import com.tc.util.Assert;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Server representation of lock management. We will need to keep track of what locks are checkedout, who has the lock
 * and who wants the lock
 */
public class LockManagerImpl implements LockManager, LockManagerMBean, TimerCallback {
  private static final TCLogger                   logger                     = TCLogging
                                                                                 .getLogger(LockManagerImpl.class);
  private static final TCLogger                   clogger                    = CustomerLogging.getDSOGenericLogger();

  public static final LockManagerErrorDescription NOT_STARTING_ERROR         = new LockManagerErrorDescription(
                                                                                                               "NOT STARTING");
  public static final LockManagerErrorDescription NOT_STARTED_ERROR          = new LockManagerErrorDescription(
                                                                                                               "NOT STARTED");
  public static final LockManagerErrorDescription IS_STARTING_ERROR          = new LockManagerErrorDescription(
                                                                                                               "IS STARTING");
  public static final LockManagerErrorDescription IS_STOPPED_ERROR           = new LockManagerErrorDescription(
                                                                                                               "IS STOPPED");

  public static final LockManagerErrorDescription LOCK_ALREADY_GRANTED_ERROR = new LockManagerErrorDescription(
                                                                                                               "LOCK ALREADY GRANTED");

  public static final int                         UNINITIALIZED_LOCK_POLICY  = 0x00;
  public static final int                         GREEDY_LOCK_POLICY         = 0x01;
  public static final int                         ALTRUISTIC_LOCK_POLICY     = 0x02;

  private static final State                      STARTING                   = new State("STARTING");
  private static final State                      STARTED                    = new State("STARTED");
  private static final State                      STOPPING                   = new State("STOPPING");
  private static final State                      STOPPED                    = new State("STOPPED");

  private State                                   status                     = STARTING;
  private final Map                               locks                      = new HashMap();
  private final DSOChannelManager                 channelManager;
  private final TCLockTimer                       waitTimer;

  private int                                     lockPolicy                 = UNINITIALIZED_LOCK_POLICY;
  // private int lockPolicy = ALTRUISTIC_LOCK_POLICY;

  private final List                              lockRequestQueue           = new ArrayList();
  private final ServerThreadContextFactory        threadContextFactory       = new ServerThreadContextFactory();
  private final L2LockStatsManager                lockStatsManager;

  public LockManagerImpl(DSOChannelManager channelManager, L2LockStatsManager lockStatsManager) {
    this.channelManager = channelManager;

    // This could maybe be combined with the lock timeout stuff, but for now
    // just use a dedicated Timer instance
    this.waitTimer = new TCLockTimerImpl();
    this.lockStatsManager = lockStatsManager;
  }

  public String dump() {
    StringWriter writer = new StringWriter();
    PrintWriter pw = new PrintWriter(writer);
    new PrettyPrinterImpl(pw).visit(this);
    writer.flush();
    return writer.toString();
  }

  public void dumpToLogger() {
    logger.info(dump());
  }

  public synchronized int getLockCount() {
    return this.locks.size();
  }

  public synchronized void verify(NodeID nodeID, LockID[] lockIDs) {
    if (!isStarted()) { return; }
    for (LockID lockID : lockIDs) {
      Lock lock = (Lock) this.locks.get(lockID);
      if (lock == null) {
        String errorMsg = " Lock is not held for " + lockID + ". Not by " + nodeID + ". Not by anyone. uhm... Nada";
        logger.warn(errorMsg);
        throw new AssertionError(errorMsg);
      }
      if (!lock.holdsSomeLock(nodeID)) { throw new AssertionError(" Lock " + lockID + " is not held by anyone in "
                                                                  + nodeID); }
    }
  }

  public synchronized void reestablishLock(LockID lockID, NodeID nid, ThreadID sourceID, int requestedLevel,
                                           Sink lockResponseSink) {
    assertStarting();
    ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(nid, sourceID);
    Lock lock = (Lock) this.locks.get(lockID);

    if (lock == null) {
      lock = new Lock(lockID, threadContext, this.lockPolicy, this.threadContextFactory, this.lockStatsManager);
      this.locks.put(lockID, lock);
    }
    lock.reestablishLock(threadContext, requestedLevel, lockResponseSink);
  }

  public synchronized boolean tryRequestLock(LockID lockID, NodeID nodeID, ThreadID sourceID, int requestedLevel,
                                             String lockType, TimerSpec timeout, Sink lockResponseSink) {
    return requestLock(lockID, nodeID, sourceID, requestedLevel, lockType, timeout, lockResponseSink, true);
  }

  private synchronized boolean requestLock(LockID lockID, NodeID nodeID, ThreadID threadID, int requestedLevel,
                                           String lockType, TimerSpec timeout, Sink lockResponseSink, boolean noBlock) {
    if (!this.channelManager.isActiveID(nodeID)) { return false; }
    if (isStarting()) {
      queueRequestLock(lockID, nodeID, threadID, requestedLevel, lockType, timeout, lockResponseSink, noBlock);
      return false;
    }
    if (!isStarted()) { return false; }
    return basicRequestLock(lockID, nodeID, threadID, requestedLevel, lockType, timeout, lockResponseSink, noBlock);
  }

  public synchronized boolean requestLock(LockID lockID, NodeID nodeID, ThreadID sourceID, int requestedLevel,
                                          String lockType, Sink lockResponseSink) {
    return requestLock(lockID, nodeID, sourceID, requestedLevel, lockType, null, lockResponseSink, false);
  }

  private boolean basicRequestLock(LockID lockID, NodeID nodeID, ThreadID threadID, int requestedLevel,
                                   String lockType, TimerSpec timeout, Sink lockResponseSink, boolean noBlock) {
    ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(nodeID, threadID);
    Lock lock = (Lock) this.locks.get(lockID);

    boolean lockAwarded = false;
    if (lock != null) {
      if (noBlock) {
        lockAwarded = lock.tryRequestLock(threadContext, requestedLevel, timeout, this.waitTimer, this,
                                          lockResponseSink);
      } else {
        lockAwarded = lock.requestLock(threadContext, requestedLevel, lockResponseSink);
      }
    } else {
      lock = new Lock(lockID, threadContext, requestedLevel, lockType, lockResponseSink, this.lockPolicy,
                      this.threadContextFactory, this.lockStatsManager);
      this.locks.put(lockID, lock);
      lockAwarded = true;
    }

    return lockAwarded;
  }

  private void queueRequestLock(LockID lockID, NodeID nodeID, ThreadID threadID, int requestedLevel, String lockType,
                                TimerSpec timeout, Sink lockResponseSink, boolean noBlock) {
    if (timeout == null) {
      this.lockRequestQueue.add(new RequestLockContext(lockID, nodeID, threadID, requestedLevel, lockType,
                                                       lockResponseSink, noBlock));
    } else {
      this.lockRequestQueue.add(new RequestLockContext(lockID, nodeID, threadID, requestedLevel, lockType, timeout,
                                                       lockResponseSink, noBlock));
    }
  }

  public synchronized void queryLock(LockID lockID, NodeID cid, ThreadID threadID, Sink lockResponseSink) {
    if (isStarting()) {
      logger.warn("QueryLock message received during lock manager is starting -- ignoring the message.\n"
                  + "Message Context: [LockID=" + lockID + ", NodeID=" + cid + ", ThreadID=" + threadID + "]");
      return;
    }
    if (!isStarted()) { return; }

    ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, threadID);
    Lock lock = (Lock) this.locks.get(lockID);
    if (lock == null) {
      // This lock is not present in the server, we still want to send the right lockID info in the response. Create one
      // for temporary use.
      lock = new Lock(lockID);
    }
    lock.queryLock(threadContext, lockResponseSink);
  }

  public synchronized void interrupt(LockID lockID, NodeID cid, ThreadID threadID) {
    if (isStarting()) {
      logger.warn("Interrupt message received during lock manager is starting -- ignoring the message.\n"
                  + "Message Context: [LockID=" + lockID + ", NodeID=" + cid + ", ThreadID=" + threadID + "]");
      return;
    }
    if (!isStarted()) { return; }

    Lock lock = getLockFor(lockID);
    ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, threadID);
    lock.interrupt(threadContext);
  }

  public synchronized void unlock(LockID id, NodeID channelID, ThreadID threadID) {
    if (isStarting()) {
      logger.warn("Unlock message received during lock manager is starting -- ignoring the message.\n"
                  + "Message Context: [LockID=" + id + ", NodeID=" + channelID + ", ThreadID=" + threadID + "]");
      return;
    }
    if (!isStarted()) { return; }

    Lock l = getLockFor(id);
    if (l.isNull()) {
      logger.warn("An attempt was made to unlock:" + id + " for channelID:" + channelID
                  + " This lock was not held. This could be do to that node being down so it may not be an error.");
      return;
    }
    basicUnlock(l, this.threadContextFactory.getOrCreate(channelID, threadID));
  }

  public synchronized void wait(LockID lid, NodeID cid, ThreadID tid, TimerSpec call, Sink lockResponseSink) {
    assertNotStopped();
    Lock lock = (Lock) this.locks.get(lid);
    if (lock != null) {
      ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, tid);
      try {
        lock.wait(threadContext, this.waitTimer, call, this, lockResponseSink);
        notifyAll();
      } catch (TCIllegalMonitorStateException e) {
        e.printStackTrace();
        // XXX: send error response back to client
        throw new ImplementMe();
      }
    } else {
      // XXX: lock doesn't exist...this is bad ;-)
      throw new ImplementMe();
    }
  }

  public synchronized void reestablishWait(LockID lid, NodeID cid, ThreadID tid, int lockLevel, TimerSpec call,
                                           Sink lockResponseSink) {
    assertStarting();
    Lock lock = (Lock) this.locks.get(lid);
    ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, tid);
    if (lock == null) {
      lock = new Lock(lid, threadContext, this.lockPolicy, this.threadContextFactory, this.lockStatsManager);
      this.locks.put(lid, lock);
    }
    lock.reestablishWait(threadContext, call, lockLevel, lockResponseSink);
  }

  public synchronized void recallCommit(LockID lid, NodeID cid, Collection lockContexts, Collection waitContexts,
                                        Collection pendingLockContexts, Collection pendingTryLockContexts,
                                        Sink lockResponseSink) {
    if (isStarting()) {
      logger.warn("RecallCommit message received during lock manager is starting -- ignoring the message.\n"
                  + "Message Context: [LockID=" + lid + ", NodeID=" + cid + "]");
      return;
    }
    if (!this.channelManager.isActiveID(cid)) {
      logger.warn("Ignoring Recall Commit message from disconnected client : " + cid + " : Lock ID : " + lid);
      return;
    }
    Lock lock = (Lock) this.locks.get(lid);
    Assert.assertNotNull(lock);

    synchronized (lock) {
      for (Iterator i = lockContexts.iterator(); i.hasNext();) {
        LockContext ctxt = (LockContext) i.next();
        ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, ctxt.getThreadID());
        lock.addRecalledHolder(threadContext, ctxt.getLockLevel());
      }

      for (Iterator i = waitContexts.iterator(); i.hasNext();) {
        WaitContext ctxt = (WaitContext) i.next();
        ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, ctxt.getThreadID());
        lock.addRecalledWaiter(threadContext, ctxt.getTimerSpec(), ctxt.getLockLevel(), lockResponseSink,
                               this.waitTimer, this);
      }

      for (Iterator i = pendingLockContexts.iterator(); i.hasNext();) {
        LockContext ctxt = (LockContext) i.next();
        ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, ctxt.getThreadID());
        lock.addRecalledPendingRequest(threadContext, ctxt.getLockLevel(), lockResponseSink);
      }

      for (Iterator i = pendingTryLockContexts.iterator(); i.hasNext();) {
        TryLockContext ctxt = (TryLockContext) i.next();
        ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, ctxt.getThreadID());
        lock.addRecalledTryLockPendingRequest(threadContext, ctxt.getLockLevel(), ((WaitContext) ctxt).getTimerSpec(),
                                              lockResponseSink, this.waitTimer, this);
      }

      ServerThreadContext threadContext = this.threadContextFactory.vmGetOrCreate(cid, ThreadID.VM_ID);
      if (lock.recallCommit(threadContext)) {
        this.locks.remove(lid);
      }
    }
  }

  public void timerTimeout(Object callbackObject) {
    synchronized (this) {
      if (isStarted() && callbackObject instanceof LockWaitContext) {
        LockWaitContext context = (LockWaitContext) callbackObject;
        context.waitTimeout();
      } else {
        logger.warn("Ignoring wait timeout for : " + callbackObject);
      }
    }
  }

  public synchronized void notify(LockID lid, NodeID cid, ThreadID tid, boolean all,
                                  NotifiedWaiters addNotifiedWaitersTo) {
    // assertStarted();
    if (!isStarted()) {
      if (isStarting()) {
        throw new AssertionError("Notify was called before the LockManager was started.");
      } else {
        logger.warn("Notify was called after shutdown sequence commenced.");
      }
    }
    Lock lock = (Lock) this.locks.get(lid);
    if (lock != null) {
      ServerThreadContext threadContext = this.threadContextFactory.getOrCreate(cid, tid);
      try {
        lock.notify(threadContext, all, addNotifiedWaitersTo);
        if (false) {
          System.err.println("LockManager.notify(" + lid + ", " + cid + ", " + tid + ", all=" + all
                             + ", notifiedWaiters=" + addNotifiedWaitersTo);
        }
      } catch (TCIllegalMonitorStateException e) {
        e.printStackTrace();
        throw new AssertionError(e);
      }
    } else {
      throw new AssertionError("Lock :" + lid + " is not present !");
    }
  }

  private void basicUnlock(Lock lock, ServerThreadContext threadContext) {
    lock.removeCurrentHold(threadContext);
    if (isStarted()) {
      boolean clear = lock.nextPending();
      if (clear) {
        this.locks.remove(lock.getLockID());
      }
    }
    notifyAll();
  }

  public synchronized boolean hasPending(LockID id) {
    return getLockFor(id).hasPending();
  }

  public synchronized void clearAllLocksFor(NodeID nid) {
    HashSet allLocks = new HashSet(this.locks.keySet());

    // NOTE: These loops might be too expensive as lock and threadContext sets grow large
    // We could keep indexes based on channel to speed things up

    for (Iterator i = allLocks.iterator(); i.hasNext();) {
      LockID lid = (LockID) i.next();
      Lock lock = getLockFor(lid);

      if (!lock.isNull()) {
        lock.clearStateForNode(nid);

        // This gets the next pending lock (if any) awarded
        basicUnlock(lock, ServerThreadContext.NULL_CONTEXT);
      }
    }
    this.lockStatsManager.clearAllStatsFor(nid);
  }

  public synchronized void enableLockStatsForNodeIfNeeded(NodeID nid) {
    this.lockStatsManager.enableStatsForNodeIfNeeded(nid);
  }

  private Lock getLockFor(LockID id) {
    Lock lock = (Lock) this.locks.get(id);
    if (lock == null) {
      logger.warn("Look up of Non-exisiting Lock for ID : " + id);
      return Lock.NULL_LOCK;
    }
    return lock;
  }

  public void scanForDeadlocks(DeadlockResults output) {
    throw new UnsupportedOperationException();
  }

  public DeadlockChain[] scanForDeadlocks() {
    final List chains = new ArrayList();
    DeadlockResults results = new DeadlockResults() {
      public void foundDeadlock(DeadlockChain chain) {
        chains.add(chain);
      }
    };

    scanForDeadlocks(results);

    return (DeadlockChain[]) chains.toArray(new DeadlockChain[chains.size()]);
  }

  public LockMBean[] getAllLocks() {
    final List copy;
    synchronized (this) {
      copy = new ArrayList(this.locks.size());
      copy.addAll(this.locks.values());
    }

    int count = 0;
    LockMBean[] rv = new LockMBean[copy.size()];
    for (Iterator i = copy.iterator(); i.hasNext();) {
      Lock lock = (Lock) i.next();
      rv[count++] = lock.getMBean(this.channelManager);
    }

    return rv;
  }

  public void start() {
    synchronized (this) {
      assertStarting();
      changeState(STARTED);
      if (this.lockPolicy == UNINITIALIZED_LOCK_POLICY) {
        this.lockPolicy = GREEDY_LOCK_POLICY;
      }

      logger.debug("START Locks re-established -- " + this.locks.size());
      for (Iterator i = this.locks.values().iterator(); i.hasNext();) {
        Lock lock = (Lock) i.next();
        lock.setLockPolicy(this.lockPolicy);
        lock.notifyStarted(this, this.waitTimer);
      }

      for (Iterator i = this.lockRequestQueue.iterator(); i.hasNext();) {
        RequestLockContext ctxt = (RequestLockContext) i.next();
        requestLock(ctxt.lockID, ctxt.nodeID, ctxt.threadID, ctxt.requestedLockLevel, ctxt.lockType, ctxt.timeout,
                    ctxt.lockResponseSink, ctxt.noBlock);
      }
      this.lockRequestQueue.clear();
    }
  }

  public synchronized void stop() throws InterruptedException {
    while (isStarting()) {
      wait();
    }
    assertStarted();
    cinfo("Stopping...");
    changeState(STOPPING);

    this.locks.clear();

    if (this.waitTimer != null) {
      this.waitTimer.shutdown();
    }
    setLockPolicy(ALTRUISTIC_LOCK_POLICY);

    changeState(STOPPED);
    cinfo("Stopped.");

  }

  public int getLockPolicy() {
    return this.lockPolicy;
  }

  public void setLockPolicy(int lockPolicy) {
    Assert.assertTrue(lockPolicy == GREEDY_LOCK_POLICY || lockPolicy == ALTRUISTIC_LOCK_POLICY);
    this.lockPolicy = lockPolicy;
    for (Iterator i = this.locks.values().iterator(); i.hasNext();) {
      Lock lock = (Lock) i.next();
      lock.setLockPolicy(this.lockPolicy);
    }
  }

  private void cinfo(Object message) {
    clogger.debug("Lock Manager: " + message);
  }

  private void changeState(State s) {
    this.status = s;
    notifyAll();
  }

  private boolean isStopped() {
    return this.status == STOPPED;
  }

  private boolean isStarted() {
    return this.status == STARTED;
  }

  private boolean isStarting() {
    return this.status == STARTING;
  }

  private void assertStarting() {
    if (!isStarting()) { throw new LockManagerError(NOT_STARTING_ERROR, "LockManager is not starting ("
                                                                        + this.status.getName() + ")"); }
  }

  private void assertStarted() {
    if (!isStarted()) { throw new LockManagerError(NOT_STARTED_ERROR, "LockManager is not started ("
                                                                      + this.status.getName() + ")"); }
  }

  private void assertNotStopped() {
    if (isStopped()) { throw new LockManagerError(IS_STOPPED_ERROR, "LockManager is stopped"); }
  }

  public static class LockManagerError extends Error {

    private final LockManagerErrorDescription desc;

    private LockManagerError(LockManagerErrorDescription desc, String msg) {
      super(msg);
      this.desc = desc;
    }

    public LockManagerErrorDescription getDescription() {
      return this.desc;
    }
  }

  public static class LockManagerErrorDescription {
    private final String name;

    private LockManagerErrorDescription(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return getClass().getName() + "[" + this.name + "]";
    }
  }

  private static class RequestLockContext {
    final LockID    lockID;
    final NodeID    nodeID;
    final ThreadID  threadID;
    final int       requestedLockLevel;
    final String    lockType;
    final boolean   noBlock;
    final Sink      lockResponseSink;
    final TimerSpec timeout;

    private RequestLockContext(LockID lockID, NodeID nodeID, ThreadID threadID, int requestedLockLevel,
                               String lockType, Sink lockResponseSink, boolean noBlock) {
      this.lockID = lockID;
      this.nodeID = nodeID;
      this.threadID = threadID;
      this.requestedLockLevel = requestedLockLevel;
      this.lockType = lockType;
      this.lockResponseSink = lockResponseSink;
      this.noBlock = noBlock;
      this.timeout = null;
    }

    private RequestLockContext(LockID lockID, NodeID nodeID, ThreadID threadID, int requestedLockLevel,
                               String lockType, TimerSpec timeout, Sink lockResponseSink, boolean noBlock) {
      this.lockID = lockID;
      this.nodeID = nodeID;
      this.threadID = threadID;
      this.requestedLockLevel = requestedLockLevel;
      this.lockType = lockType;
      this.lockResponseSink = lockResponseSink;
      this.noBlock = noBlock;
      this.timeout = timeout;
    }

    @Override
    public String toString() {
      return "RequestLockContext [ " + this.lockID + "," + this.nodeID + "," + this.threadID + ","
             + LockLevel.toString(this.requestedLockLevel) + ", " + this.noBlock + ", " + this.timeout + " ]";
    }
  }

  private static class State {
    private final String name;

    private State(String name) {
      this.name = name;
    }

    public String getName() {
      return this.name;
    }

    @Override
    public String toString() {
      return getClass().getName() + "[" + this.name + "]";
    }
  }

  public synchronized PrettyPrinter prettyPrint(PrettyPrinter out) {
    out.println(getClass().getName());
    out.indent().println("locks: " + this.locks.size());
    for (Iterator i = this.locks.values().iterator(); i.hasNext();) {
      out.println(i.next());
    }
    return out;
  }

}
