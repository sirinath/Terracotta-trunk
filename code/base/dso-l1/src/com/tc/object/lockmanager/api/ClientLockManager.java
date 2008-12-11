/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.lockmanager.api;

import com.tc.logging.DumpHandler;
import com.tc.net.NodeID;
import com.tc.object.lockmanager.impl.GlobalLockInfo;
import com.tc.object.session.SessionID;
import com.tc.object.tx.TimerSpec;
import com.tc.text.PrettyPrintable;
import com.tc.util.runtime.LockInfoByThreadID;

/**
 * Simple lock manager for the client
 * 
 */
public interface ClientLockManager extends DumpHandler, PrettyPrintable {

  /**
   * obtain a lock
   */
  public void lock(LockID id, ThreadID threadID, int lockType, String lockObjectType, String contextInfo);

  public void lockInterruptibly(LockID id, ThreadID threadID, int lockType, String lockObjectType, String contextInfo) throws InterruptedException;

  public boolean tryLock(LockID id, ThreadID threadID, TimerSpec timeout, int lockType, String lockObjectType);

  /**
   * releases the lock so that others can have at it
   */
  public void unlock(LockID id, ThreadID threadID);

  /**
   * awards the lock to the threadID
   */
  public void awardLock(NodeID nid, SessionID sessionID, LockID id, ThreadID threadID, int type);

  public void cannotAwardLock(NodeID nid, SessionID sessionID, LockID id, ThreadID threadID, int type);

  public LockID lockIDFor(String id);

  public void wait(LockID lockID, ThreadID threadID, TimerSpec call, Object waitObject, WaitListener listener)
      throws InterruptedException;

  public void waitTimedOut(LockID lockID, ThreadID threadID);

  /**
   * Returns true if this notification should be send to the server for handling. This nofication is not needed to be
   * sent to the server if all is false and we have notified 1 waiter locally.
   */
  public Notify notify(LockID lockID, ThreadID threadID, boolean all);

  /**
   * Makes the lock wait for the given lock and thread a pending request.
   */
  public void notified(LockID lockID, ThreadID threadID);

  /**
   * Recalls a greedy Lock that was awarded earlier. If leaseTimeInMs is zero, then there is no lease
   */
  public void recall(LockID lockID, ThreadID threadID, int level, int leaseTimeInMs);

  public void addAllLocksTo(LockInfoByThreadID lockInfo);

  public int queueLength(LockID lockID, ThreadID threadID);

  public int waitLength(LockID lockID, ThreadID threadID);

  public int localHeldCount(LockID lockID, int lockLevel, ThreadID threadID);

  public boolean isLocked(LockID lockID, ThreadID threadID, int lockLevel);

  public void queryLockCommit(ThreadID threadID, GlobalLockInfo globalLockInfo);

  public void setLockStatisticsConfig(int traceDepth, int gatherInterval);

  public void setLockStatisticsEnabled(boolean statEnable);

  public void requestLockSpecs();
}
