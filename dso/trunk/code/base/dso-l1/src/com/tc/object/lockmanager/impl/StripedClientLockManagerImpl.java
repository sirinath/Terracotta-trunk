/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.lockmanager.impl;

import com.tc.logging.TCLogger;
import com.tc.logging.TextDecoratorTCLogger;
import com.tc.management.ClientLockStatManager;
import com.tc.net.NodeID;
import com.tc.object.handshakemanager.ClientHandshakeCallback;
import com.tc.object.lockmanager.api.ClientLockManager;
import com.tc.object.lockmanager.api.ClientLockManagerConfig;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.Notify;
import com.tc.object.lockmanager.api.RemoteLockManager;
import com.tc.object.lockmanager.api.TCLockTimer;
import com.tc.object.lockmanager.api.ThreadID;
import com.tc.object.lockmanager.api.WaitListener;
import com.tc.object.msg.ClientHandshakeMessage;
import com.tc.object.session.SessionID;
import com.tc.object.session.SessionManager;
import com.tc.object.tx.TimerSpec;
import com.tc.text.PrettyPrinter;
import com.tc.util.runtime.LockInfoByThreadID;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;

public class StripedClientLockManagerImpl implements ClientLockManager, ClientHandshakeCallback {

  private final ClientLockManagerImpl lockManagers[];
  private final int                   segmentShift;
  private final int                   segmentMask;
  private final TCLogger              logger;

  public StripedClientLockManagerImpl(TCLogger logger, RemoteLockManager remoteLockManager,
                                      SessionManager sessionManager, ClientLockStatManager lockStatManager,
                                      ClientLockManagerConfig clientLockManagerConfig) {
    this.logger = logger;
    int stripedCount = clientLockManagerConfig.getStripedCount();

    int sshift = 0;
    int ssize = 1;
    while (ssize < stripedCount) {
      ++sshift;
      ssize <<= 1;
    }
    segmentShift = 32 - sshift;
    segmentMask = ssize - 1;

    lockManagers = new ClientLockManagerImpl[ssize];
    TCLockTimer waitTimer = new TCLockTimerImpl();
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i] = new ClientLockManagerImpl(new TextDecoratorTCLogger(logger, "LM[" + i + "]"),
                                                  remoteLockManager, sessionManager, lockStatManager,
                                                  clientLockManagerConfig, waitTimer);
    }
  }

  /**
   * Applies a supplemental hash function to a given hashCode, which defends against poor quality hash functions.
   */
  private static int hash(int h) {
    h += (h << 15) ^ 0xffffcd7d;
    h ^= (h >>> 10);
    h += (h << 3);
    h ^= (h >>> 6);
    h += (h << 2) + (h << 14);
    return h ^ (h >>> 16);
  }

  /**
   * Returns the segment that should be used for key with given hash
   * 
   * @param hash the hash code for the key
   * @return the segment
   */
  final ClientLockManagerImpl lockManagerFor(LockID lock) {
    return lockManagerFor(lock.asString());
  }

  private ClientLockManagerImpl lockManagerFor(String lockID) {
    int hash = hash(lockID.hashCode());
    return lockManagers[(hash >>> segmentShift) & segmentMask];
  }

  public synchronized void pause(NodeID remote, int disconnected) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].pause(remote, disconnected);
    }
  }

  public synchronized void unpause(NodeID remote, int disconnected) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].unpause(remote, disconnected);
    }
  }

  public void initializeHandshake(NodeID thisNode, NodeID remoteNode, ClientHandshakeMessage handshakeMessage) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].initializeHandshake(thisNode, remoteNode, handshakeMessage);
    }
  }

  public void lock(LockID id, ThreadID threadID, int lockType, String lockObjectType, String contextInfo) {
    lockManagerFor(id).lock(id, threadID, lockType, lockObjectType, contextInfo);
  }

  public void awardLock(NodeID nid, SessionID sessionID, LockID id, ThreadID threadID, int type) {
    lockManagerFor(id).awardLock(nid, sessionID, id, threadID, type);
  }

  public void cannotAwardLock(NodeID nid, SessionID sessionID, LockID id, ThreadID threadID, int type) {
    lockManagerFor(id).cannotAwardLock(nid, sessionID, id, threadID, type);
  }

  public boolean isLocked(LockID lockID, ThreadID threadID, int lockLevel) {
    return lockManagerFor(lockID).isLocked(lockID, threadID, lockLevel);
  }

  public int localHeldCount(LockID lockID, int lockLevel, ThreadID threadID) {
    return lockManagerFor(lockID).localHeldCount(lockID, lockLevel, threadID);
  }

  public LockID lockIDFor(String id) {
    return lockManagerFor(id).lockIDFor(id);
  }

  public void notified(LockID lockID, ThreadID threadID) {
    lockManagerFor(lockID).notified(lockID, threadID);
  }

  public Notify notify(LockID lockID, ThreadID threadID, boolean all) {
    return lockManagerFor(lockID).notify(lockID, threadID, all);
  }

  public int queueLength(LockID lockID, ThreadID threadID) {
    return lockManagerFor(lockID).queueLength(lockID, threadID);
  }

  public void recall(LockID lockID, ThreadID threadID, int level, int leaseTimeInMs) {
    lockManagerFor(lockID).recall(lockID, threadID, level, leaseTimeInMs);
  }

  public void lockInterruptibly(LockID id, ThreadID threadID, int lockType, String lockObjectType, String contextInfo) throws InterruptedException {
    lockManagerFor(id).lockInterruptibly(id, threadID, lockType, lockObjectType, contextInfo);
  }
  
  public boolean tryLock(LockID id, ThreadID threadID, TimerSpec timeout, int lockType, String lockObjectType) {
    return lockManagerFor(id).tryLock(id, threadID, timeout, lockType, lockObjectType);
  }

  public void unlock(LockID id, ThreadID threadID) {
    lockManagerFor(id).unlock(id, threadID);
  }

  public void wait(LockID lockID, ThreadID threadID, TimerSpec call, Object waitObject, WaitListener listener)
      throws InterruptedException {
    lockManagerFor(lockID).wait(lockID, threadID, call, waitObject, listener);
  }

  public int waitLength(LockID lockID, ThreadID threadID) {
    return lockManagerFor(lockID).waitLength(lockID, threadID);
  }

  public void waitTimedOut(LockID lockID, ThreadID threadID) {
    lockManagerFor(lockID).waitTimedOut(lockID, threadID);
  }

  public void addAllLocksTo(LockInfoByThreadID lockInfo) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].addAllLocksTo(lockInfo);
    }
  }

  public Collection addAllHeldLocksTo(Collection c) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].addAllHeldLocksTo(c);
    }
    return c;
  }

  public Collection addAllPendingLockRequestsTo(Collection c) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].addAllPendingLockRequestsTo(c);
    }
    return c;
  }

  public Collection addAllPendingTryLockRequestsTo(Collection c) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].addAllPendingTryLockRequestsTo(c);
    }
    return c;
  }

  public Collection addAllWaitersTo(Collection c) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].addAllWaitersTo(c);
    }
    return c;
  }

  public void queryLockCommit(ThreadID threadID, GlobalLockInfo globalLockInfo) {
    lockManagerFor(globalLockInfo.getLockID()).queryLockCommit(threadID, globalLockInfo);
  }

  public void requestLockSpecs() {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].requestLockSpecs();
    }
  }

  public void setLockStatisticsConfig(int traceDepth, int gatherInterval) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].setLockStatisticsConfig(traceDepth, gatherInterval);
    }
  }

  public void setLockStatisticsEnabled(boolean statEnable) {
    for (int i = 0; i < lockManagers.length; i++) {
      lockManagers[i].setLockStatisticsEnabled(statEnable);
    }
  }

  public String dump() {
    StringBuffer sb = new StringBuffer("StripedClientLockManagerImpl : { \n");
    for (int i = 0; i < lockManagers.length; i++) {
      sb.append('[').append(i).append("] = ");
      sb.append(lockManagers[i].dump()).append("\n");
    }
    sb.append("}");
    return sb.toString();
  }

  public void dump(Writer writer) {
    try {
      writer.write(dump());
      writer.flush();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  public void dumpToLogger() {
    logger.info(dump());
  }

  public PrettyPrinter prettyPrint(PrettyPrinter out) {
    out.println(getClass().getName());
    for (int i = 0; i < lockManagers.length; i++) {
      out.indent().println("[ " + i + "] = ");
      lockManagers[i].prettyPrint(out);
    }
    return out;
  }

}
