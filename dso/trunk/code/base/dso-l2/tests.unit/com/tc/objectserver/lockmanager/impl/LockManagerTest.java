/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.lockmanager.impl;

import org.apache.commons.io.output.NullOutputStream;

import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;

import com.tc.exception.TCLockUpgradeNotSupportedError;
import com.tc.management.L2LockStatsManager;
import com.tc.management.lock.stats.L2LockStatisticsManagerImpl;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.net.protocol.tcm.ChannelID;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.LockLevel;
import com.tc.object.lockmanager.api.ServerThreadID;
import com.tc.object.lockmanager.api.ThreadID;
import com.tc.object.lockmanager.impl.LockHolder;
import com.tc.object.tx.TimerSpec;
import com.tc.objectserver.api.TestSink;
import com.tc.objectserver.context.LockResponseContext;
import com.tc.objectserver.lockmanager.api.DeadlockChain;
import com.tc.objectserver.lockmanager.api.DeadlockResults;
import com.tc.objectserver.lockmanager.api.LockMBean;
import com.tc.objectserver.lockmanager.api.NullChannelManager;
import com.tc.objectserver.lockmanager.api.ServerLockRequest;
import com.tc.objectserver.lockmanager.api.Waiter;
import com.tc.text.Banner;
import com.tc.util.concurrent.ThreadUtil;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import junit.framework.TestCase;

/**
 * @author steve
 */
public class LockManagerTest extends TestCase {
  private TestSink         sink;
  private LockManagerImpl  lockManager;
  private Random           random     = new Random();

  final int                numLocks   = 30;
  final int                numThreads = 15;
  private LockID[]         locks      = makeUniqueLocks(numLocks);
  private ServerThreadID[] txns       = makeUniqueTxns(numThreads);

  protected void setUp() throws Exception {
    super.setUp();
    resetLockManager();
    sink = new TestSink();
  }

  private void resetLockManager() {
    resetLockManager(false);
  }

  private void resetLockManager(boolean start) {
    if (lockManager != null) {
      try {
        lockManager.stop();
      } catch (InterruptedException e) {
        fail();
      }
    }

    lockManager = new LockManagerImpl(new NullChannelManager(), L2LockStatsManager.NULL_LOCK_STATS_MANAGER);
    lockManager.setLockPolicy(LockManagerImpl.ALTRUISTIC_LOCK_POLICY);
    if (start) {
      lockManager.start();
    }
  }

  protected void tearDown() throws Exception {
    assertEquals(0, lockManager.getLockCount());
    super.tearDown();
  }

  public void testLockMBean() throws IOException {

    final long start = System.currentTimeMillis();
    final ClientID cid1 = new ClientID(new ChannelID(1));
    final ClientID cid2 = new ClientID(new ChannelID(2));

    LockID lid1 = new LockID("1");
    LockID lid2 = new LockID("2");
    LockID lid3 = new LockID("3");
    ThreadID tid1 = new ThreadID(1);
    TimerSpec wait = new TimerSpec(Integer.MAX_VALUE);

    L2LockStatsManager lockStatsManager = new L2LockStatisticsManagerImpl();
    lockManager = new LockManagerImpl(new NullChannelManager() {
      public String getChannelAddress(NodeID nid) {
        if (cid1.equals(nid)) { return "127.0.0.1:6969"; }
        return "no longer connected";
      }
    }, lockStatsManager);

    lockManager.setLockPolicy(LockManagerImpl.ALTRUISTIC_LOCK_POLICY);
    lockManager.start();
    lockStatsManager.start(new NullChannelManager());

    lockManager.requestLock(lid1, cid1, tid1, LockLevel.WRITE, String.class.getName(), sink); // hold
    lockManager.requestLock(lid1, cid2, tid1, LockLevel.WRITE, String.class.getName(), sink); // pending

    lockManager.requestLock(lid2, cid1, tid1, LockLevel.READ, String.class.getName(), sink); // hold
    lockManager.requestLock(lid2, cid2, tid1, LockLevel.READ, String.class.getName(), sink); // hold
    try {
      lockManager.requestLock(lid2, cid1, tid1, LockLevel.WRITE, String.class.getName(), sink); // try upgrade and fail
      throw new AssertionError("Should have thrown an TCLockUpgradeNotSupportedError.");
    } catch (TCLockUpgradeNotSupportedError e) {
      //
    }

    lockManager.requestLock(lid3, cid1, tid1, LockLevel.WRITE, String.class.getName(), sink); // hold
    lockManager.wait(lid3, cid1, tid1, wait, sink); // wait

    LockMBean[] lockBeans = lockManager.getAllLocks();
    assertEquals(3, lockBeans.length);
    sortLocksByID(lockBeans);

    LockMBean bean1 = lockBeans[0];
    LockMBean bean2 = lockBeans[1];
    LockMBean bean3 = lockBeans[2];
    testSerialize(bean1);
    testSerialize(bean2);
    testSerialize(bean3);

    validateBean1(bean1, start);
    validateBean2(bean2, start);
    validateBean3(bean3, start, wait);

    lockManager.clearAllLocksFor(cid1);
    lockManager.clearAllLocksFor(cid2);
  }

  private void validateBean3(LockMBean bean3, long time, TimerSpec wait) {
    LockHolder[] holders = bean3.getHolders();
    ServerLockRequest[] reqs = bean3.getPendingRequests();
    Waiter[] waiters = bean3.getWaiters();
    assertEquals(0, holders.length);
    assertEquals(0, reqs.length);
    assertEquals(1, waiters.length);

    Waiter waiter = waiters[0];
    assertEquals(wait.toString(), waiter.getWaitInvocation());
    assertTrue(waiter.getStartTime() >= time);
    assertEquals("127.0.0.1:6969", waiter.getChannelAddr());
    assertEquals(new ThreadID(1), waiter.getThreadID());
  }

  private void validateBean2(LockMBean bean2, long time) {
    LockHolder[] holders = bean2.getHolders();
    ServerLockRequest[] reqs = bean2.getPendingRequests();
    Waiter[] waiters = bean2.getWaiters();
    assertEquals(2, holders.length);
    assertEquals(0, reqs.length);
    assertEquals(0, waiters.length);

    LockHolder holder = holders[0];
    assertEquals(LockLevel.toString(LockLevel.READ), holder.getLockLevel());
    assertTrue(holder.getTimeAcquired() >= time);
    assertEquals("127.0.0.1:6969", holder.getChannelAddr());
    assertEquals(new ThreadID(1), holder.getThreadID());

    holder = holders[1];
    assertEquals(LockLevel.toString(LockLevel.READ), holder.getLockLevel());
    assertTrue(holder.getTimeAcquired() >= time);
    assertEquals("no longer connected", holder.getChannelAddr());
    assertEquals(new ThreadID(1), holder.getThreadID());

    // ServerLockRequest up = upgrades[0];
    // assertEquals(LockLevel.toString(LockLevel.WRITE), up.getLockLevel());
    // assertTrue(up.getRequestTime() >= time);
    // assertEquals(new ChannelID(1), up.getChannelID());
    // assertEquals("127.0.0.1:6969", up.getChannelAddr());
    // assertEquals(new ThreadID(1), up.getThreadID());
  }

  private void validateBean1(LockMBean bean1, long time) {
    LockHolder[] holders = bean1.getHolders();
    ServerLockRequest[] reqs = bean1.getPendingRequests();
    Waiter[] waiters = bean1.getWaiters();
    assertEquals(1, holders.length);
    assertEquals(1, reqs.length);
    assertEquals(0, waiters.length);

    LockHolder holder = holders[0];
    assertEquals(LockLevel.toString(LockLevel.WRITE), holder.getLockLevel());
    assertTrue(holder.getTimeAcquired() >= time);
    assertEquals(new ThreadID(1), holder.getThreadID());
    assertEquals("127.0.0.1:6969", holder.getChannelAddr());

    ServerLockRequest req = reqs[0];
    assertEquals(LockLevel.toString(LockLevel.WRITE), req.getLockLevel());
    assertTrue(req.getRequestTime() >= time);
    assertEquals("no longer connected", req.getChannelAddr());
    assertEquals(new ThreadID(1), req.getThreadID());
  }

  private void testSerialize(Object o) throws IOException {
    ObjectOutputStream oos = new ObjectOutputStream(new NullOutputStream());
    oos.writeObject(o);
    oos.close();
  }

  private void sortLocksByID(LockMBean[] lockBeans) {
    Arrays.sort(lockBeans, new Comparator() {
      public int compare(Object o1, Object o2) {
        LockMBean l1 = (LockMBean) o1;
        LockMBean l2 = (LockMBean) o2;

        String id1 = l1.getLockName();
        String id2 = l2.getLockName();

        return id1.compareTo(id2);
      }
    });
  }

  public void testReestablishWait() throws Exception {
    LockID lockID1 = new LockID("my lock");
    ClientID nid1 = new ClientID(new ChannelID(1));
    ThreadID tx1 = new ThreadID(1);
    ThreadID tx2 = new ThreadID(2);

    try {
      assertEquals(0, lockManager.getLockCount());
      long waitTime = 1000;
      TimerSpec waitCall = new TimerSpec(waitTime);
      TestSink responseSink = new TestSink();
      long t0 = System.currentTimeMillis();
      lockManager.reestablishWait(lockID1, nid1, tx1, LockLevel.WRITE, waitCall, responseSink);
      lockManager.reestablishWait(lockID1, nid1, tx2, LockLevel.WRITE, waitCall, responseSink);
      lockManager.start();

      LockResponseContext ctxt = (LockResponseContext) responseSink.waitForAdd(waitTime * 3);
      assertTrue(ctxt != null);
      assertTrue(System.currentTimeMillis() - t0 >= waitTime);
      assertResponseContext(lockID1, nid1, tx1, LockLevel.WRITE, ctxt);
      assertTrue(ctxt.isLockWaitTimeout());
      ThreadUtil.reallySleep(waitTime * 3);
      assertEquals(3, responseSink.size()); // 2 wait timeouts and 1 award
      ctxt = (LockResponseContext) responseSink.take();
      LockResponseContext ctxt1 = (LockResponseContext) responseSink.take();
      LockResponseContext ctxt2 = (LockResponseContext) responseSink.take();
      assertTrue((ctxt1.isLockAward() && ctxt2.isLockWaitTimeout())
                 || (ctxt2.isLockAward() && ctxt1.isLockWaitTimeout()));

    } finally {
      lockManager = null;
      resetLockManager();
    }
  }

  public void testReestablishLockAfterReestablishWait() throws Exception {
    LockID lockID1 = new LockID("my lock");
    ClientID cid1 = new ClientID(new ChannelID(1));
    ThreadID tx1 = new ThreadID(1);
    ThreadID tx2 = new ThreadID(2);
    int requestedLevel = LockLevel.WRITE;
    TimerSpec waitCall = new TimerSpec();
    try {
      TestSink responseSink = new TestSink();
      assertEquals(0, lockManager.getLockCount());
      lockManager.reestablishWait(lockID1, cid1, tx1, LockLevel.WRITE, waitCall, responseSink);
      assertEquals(1, lockManager.getLockCount());
      assertEquals(0, responseSink.getInternalQueue().size());

      // now try to award the lock to the same client-transaction
      try {
        lockManager.reestablishLock(lockID1, cid1, tx1, requestedLevel, responseSink);
        fail("Should have thrown an AssertionError.");
      } catch (AssertionError e) {
        // expected
      }
      // now try to reestablish the same lock from a different transaction. It
      // sould succeed
      assertEquals(1, lockManager.getLockCount());
      lockManager.reestablishLock(lockID1, cid1, tx2, requestedLevel, responseSink);
    } finally {
      lockManager = null;
      resetLockManager();
    }
  }

  public void testReestablishReadLock() throws Exception {
    LockID lockID1 = new LockID("my lock");
    ClientID cid1 = new ClientID(new ChannelID(1));
    ThreadID tx1 = new ThreadID(1);
    ThreadID tx2 = new ThreadID(2);
    ThreadID tx3 = new ThreadID(3);
    int requestedLevel = LockLevel.READ;

    try {
      TestSink responseSink = new TestSink();
      assertEquals(0, lockManager.getLockCount());

      lockManager.reestablishLock(lockID1, cid1, tx1, requestedLevel, responseSink);
      assertEquals(1, lockManager.getLockCount());

      // now reestablish the same read lock in another transaction. It should
      // succeed.
      lockManager.reestablishLock(lockID1, cid1, tx2, requestedLevel, responseSink);
      assertEquals(1, lockManager.getLockCount());

      // now reestablish the the same write lock. It should fail.
      try {
        lockManager.reestablishLock(lockID1, cid1, tx3, LockLevel.WRITE, responseSink);
        fail("Should have thrown a LockManagerError.");
      } catch (AssertionError e) {
        //
      }

    } finally {
      // this needs to be done for tearDown() to pass.
      lockManager = null;
      resetLockManager();
    }

    try {
      TestSink responseSink = new TestSink();
      assertEquals(0, lockManager.getLockCount());
      lockManager.reestablishLock(lockID1, cid1, tx1, LockLevel.WRITE, responseSink);
      assertEquals(1, lockManager.getLockCount());

      // now reestablish a read lock. This should fail.
      responseSink = new TestSink();
      try {
        lockManager.reestablishLock(lockID1, cid1, tx2, LockLevel.READ, responseSink);
        fail("Should have thrown a LockManagerError");
      } catch (AssertionError e) {
        //
      }

    } finally {
      lockManager = null;
      resetLockManager();
    }
  }

  public void testReestablishWriteLock() throws Exception {

    LockID lockID1 = new LockID("my lock");
    LockID lockID2 = new LockID("my other lock");
    ClientID cid1 = new ClientID(new ChannelID(1));
    ClientID cid2 = new ClientID(new ChannelID(2));
    ThreadID tx1 = new ThreadID(1);
    ThreadID tx2 = new ThreadID(2);
    int requestedLevel = LockLevel.WRITE;

    try {
      TestSink responseSink = new TestSink();
      assertEquals(0, lockManager.getLockCount());
      lockManager.reestablishLock(lockID1, cid1, tx1, requestedLevel, responseSink);
      assertEquals(1, lockManager.getLockCount());

      try {
        lockManager.reestablishLock(lockID1, cid2, tx2, requestedLevel, responseSink);
        fail("Expected a LockManagerError!");
      } catch (AssertionError e) {
        //
      }

      // try to reestablish another lock. It should succeed.
      lockManager.reestablishLock(lockID2, cid1, tx1, requestedLevel, responseSink);

      lockManager.start();
      // you shouldn't be able to call reestablishLock after the lock manager
      // has started.
      try {
        lockManager.reestablishLock(lockID1, cid1, tx1, requestedLevel, null);
        fail("Should have thrown a LockManagerError");
      } catch (Error e) {
        //
      }

    } finally {
      // this needs to be done for tearDown() to pass.
      lockManager = null;
      resetLockManager();
    }
  }

  // private void assertResponseSink(LockID lockID, ChannelID channel, TransactionID tx, int requestedLevel,
  // TestSink responseSink) {
  // assertEquals(1, responseSink.getInternalQueue().size());
  // LockResponseContext ctxt = (LockResponseContext) responseSink.getInternalQueue().get(0);
  // assertResponseContext(lockID, channel, tx, requestedLevel, ctxt);
  // }

  private void assertResponseContext(LockID lockID, NodeID nid, ThreadID tx1, int requestedLevel,
                                     LockResponseContext ctxt) {
    assertEquals(lockID, ctxt.getLockID());
    assertEquals(nid, ctxt.getNodeID());
    assertEquals(tx1, ctxt.getThreadID());
    assertEquals(requestedLevel, ctxt.getLockLevel());
  }

  public void testWaitTimeoutsIgnoredDuringStartup() throws Exception {
    LockID lockID = new LockID("my lcok");
    ClientID cid1 = new ClientID(new ChannelID(1));
    ThreadID tx1 = new ThreadID(1);
    try {
      long waitTime = 1000;
      TimerSpec waitInvocation = new TimerSpec(waitTime);
      TestSink responseSink = new TestSink();
      lockManager.reestablishWait(lockID, cid1, tx1, LockLevel.WRITE, waitInvocation, responseSink);

      LockResponseContext ctxt = (LockResponseContext) responseSink.waitForAdd(waitTime * 2);
      assertNull(ctxt);

      lockManager.start();
      ctxt = (LockResponseContext) responseSink.waitForAdd(0);
      assertNotNull(ctxt);
    } finally {
      lockManager = null;
      resetLockManager();
    }
  }

  public void testWaitTimeoutsIgnoredDuringShutdown() throws InterruptedException {

    ClientID cid = new ClientID(new ChannelID(1));
    LockID lockID = new LockID("1");
    ThreadID txID = new ThreadID(1);

    lockManager.start();
    boolean granted = lockManager.requestLock(lockID, cid, txID, LockLevel.WRITE, String.class.getName(), sink);
    assertTrue(granted);

    lockManager.wait(lockID, cid, txID, new TimerSpec(1000), sink);
    lockManager.stop();

    assertFalse(lockManager.hasPending(lockID));
    assertEquals(0, lockManager.getLockCount());

    ThreadUtil.reallySleep(1500);

    assertFalse(lockManager.hasPending(lockID));
    assertEquals(0, lockManager.getLockCount());
  }

  public void testOffBlocksUntilNoOutstandingLocksViaWait() throws Exception {
    // this is no longer expected behavior
    if (true) return;
    List queue = sink.getInternalQueue();
    ClientID cid = new ClientID(new ChannelID(1));
    LockID lockID = new LockID("1");
    ThreadID txID = new ThreadID(1);

    final LinkedQueue shutdownSteps = new LinkedQueue();
    ShutdownThread shutdown = new ShutdownThread(shutdownSteps);

    try {
      lockManager.start();
      lockManager.requestLock(lockID, cid, txID, LockLevel.WRITE, String.class.getName(), sink);
      assertEquals(1, queue.size());

      shutdown.start();
      shutdownSteps.take();
      ThreadUtil.reallySleep(500);
      // make sure shutdown didn't complete.
      assertTrue(shutdownSteps.peek() == null);

      // make sure that waiting on an outstanding lock causes the lock to be
      // released and allows shutdown to
      // complete.
      lockManager.wait(lockID, cid, new ThreadID(1), new TimerSpec(), sink);
      shutdownSteps.take();

    } finally {
      lockManager.clearAllLocksFor(cid);
    }
  }

  public void testOffDoesNotBlockUntilNoOutstandingLocksViaUnlock() throws Exception {
    List queue = sink.getInternalQueue();
    ClientID cid1 = new ClientID(new ChannelID(1));
    LockID lock1 = new LockID("1");
    ThreadID tx1 = new ThreadID(1);

    final LinkedQueue shutdownSteps = new LinkedQueue();
    ShutdownThread shutdown = new ShutdownThread(shutdownSteps);
    try {
      lockManager.start();
      lockManager.requestLock(lock1, cid1, tx1, LockLevel.WRITE, String.class.getName(), sink);
      assertEquals(1, queue.size());

      shutdown.start();
      shutdownSteps.take();
      ThreadUtil.reallySleep(1000);
      shutdownSteps.take();
    } finally {
      lockManager = null;
      resetLockManager();
    }
  }

  public void testOffCancelsWaits() throws Exception {
    // implement me.
  }

  public void testOffStopsGrantingNewLocks() throws Exception {
    List queue = sink.getInternalQueue();
    ClientID cid = new ClientID(new ChannelID(1));
    LockID lockID = new LockID("1");
    ThreadID txID = new ThreadID(1);
    try {
      // Test that the normal case works as expected...
      lockManager.start();
      lockManager.requestLock(lockID, cid, txID, LockLevel.WRITE, String.class.getName(), sink);
      assertEquals(1, queue.size());
      queue.clear();
      lockManager.unlock(lockID, cid, txID);
      lockManager.requestLock(lockID, cid, txID, LockLevel.WRITE, String.class.getName(), sink);
      assertEquals(1, queue.size());
      lockManager.unlock(lockID, cid, txID);

      // Call shutdown and make sure that the lock isn't granted via the
      // "requestLock" method
      queue.clear();
      lockManager.stop();
      lockManager.requestLock(lockID, cid, txID, LockLevel.WRITE, String.class.getName(), sink);
      assertEquals(0, queue.size());
    } finally {
      lockManager.clearAllLocksFor(cid);
    }
  }

  public void testRequestDoesntGrantPendingLocks() throws Exception {
    List queue = sink.getInternalQueue();
    ClientID cid = new ClientID(new ChannelID(1));
    LockID lockID = new LockID("1");
    ThreadID txID = new ThreadID(1);

    try {
      lockManager.start();
      // now try stacking locks and make sure that calling unlock doesn't grant
      // the pending locks.
      lockManager.requestLock(lockID, cid, txID, LockLevel.WRITE, String.class.getName(), sink);
      queue.clear();
      lockManager.requestLock(lockID, cid, new ThreadID(2), LockLevel.WRITE, String.class.getName(), sink);
      // the second lock should be pending.
      assertEquals(0, queue.size());
    } finally {
      lockManager = null;
      resetLockManager();
    }
  }

  public void testUnlockIgnoredDuringShutdown() throws Exception {
    List queue = sink.getInternalQueue();
    ClientID cid = new ClientID(new ChannelID(1));
    LockID lockID = new LockID("1");
    ThreadID txID = new ThreadID(1);
    try {
      lockManager.start();
      // now try stacking locks and make sure that calling unlock doesn't grant
      // the pending locks.
      lockManager.requestLock(lockID, cid, txID, LockLevel.WRITE, String.class.getName(), sink);
      queue.clear();
      lockManager.requestLock(lockID, cid, new ThreadID(2), LockLevel.WRITE, String.class.getName(), sink);
      // the second lock should be pending.
      assertEquals(0, queue.size());

      lockManager.stop();

      // unlock the first lock
      lockManager.unlock(lockID, cid, txID);
      // the second lock should still be pending
      assertEquals(0, queue.size());

    } finally {
      lockManager = null;
      resetLockManager();
    }
  }

  public void testLockManagerBasics() {
    LockID l1 = new LockID("1");
    ClientID c1 = new ClientID(new ChannelID(1));
    ThreadID s1 = new ThreadID(0);

    ClientID c2 = new ClientID(new ChannelID(2));
    ClientID c3 = new ClientID(new ChannelID(3));
    ClientID c4 = new ClientID(new ChannelID(4));
    lockManager.start();
    lockManager.requestLock(l1, c1, s1, LockLevel.WRITE, String.class.getName(), sink);
    assertTrue(sink.size() == 1);
    System.out.println(sink.getInternalQueue().remove(0));

    lockManager.requestLock(l1, c2, s1, LockLevel.WRITE, String.class.getName(), sink);
    assertTrue(sink.size() == 0);
    lockManager.unlock(l1, c1, s1);
    assertTrue(sink.size() == 1);
    System.out.println(sink.getInternalQueue().remove(0));

    lockManager.requestLock(l1, c3, s1, LockLevel.READ, String.class.getName(), sink);
    assertTrue(sink.size() == 0);
    assertTrue(lockManager.hasPending(l1));
    lockManager.unlock(l1, c2, s1);
    assertTrue(sink.size() == 1);
    assertFalse(lockManager.hasPending(l1));

    lockManager.requestLock(l1, c4, s1, LockLevel.WRITE, String.class.getName(), sink);
    assertTrue(lockManager.hasPending(l1));
    lockManager.unlock(l1, c3, s1);
    assertFalse(lockManager.hasPending(l1));
    lockManager.unlock(l1, c4, s1);
  }

  public void testDeadLock1() {
    if (true) {
      Banner.warnBanner("DEADLOCK tests DISABLED");
      return;
    }

    // A simple deadlock. Thread 1 holds lock1, wants lock2. Thread2 holds
    // lock2, wants lock1

    LockID l1 = new LockID("1");
    LockID l2 = new LockID("2");
    ClientID c1 = new ClientID(new ChannelID(1));

    ThreadID s1 = new ThreadID(1);
    ThreadID s2 = new ThreadID(2);

    ServerThreadID thread1 = new ServerThreadID(c1, s1);
    ServerThreadID thread2 = new ServerThreadID(c1, s2);

    lockManager.start();
    // thread1 gets lock1
    lockManager.requestLock(l1, c1, s1, LockLevel.WRITE, String.class.getName(), sink);
    // thread2 gets lock2
    lockManager.requestLock(l2, c1, s2, LockLevel.WRITE, String.class.getName(), sink);
    // thread1 trys to get lock2 (blocks)
    lockManager.requestLock(l2, c1, s1, LockLevel.WRITE, String.class.getName(), sink);
    // thread2 trys to get lock1 (blocks)
    lockManager.requestLock(l1, c1, s2, LockLevel.WRITE, String.class.getName(), sink);

    TestDeadlockResults deadlocks = new TestDeadlockResults();
    lockManager.scanForDeadlocks(deadlocks);

    assertEquals(1, deadlocks.chains.size());
    Map check = new HashMap();
    check.put(thread1, l2);
    check.put(thread2, l1);
    assertSpecificDeadlock((DeadlockChain) deadlocks.chains.get(0), check);

    // test the mgmt interface too
    DeadlockChain[] results = lockManager.scanForDeadlocks();
    assertEquals(1, results.length);
    check = new HashMap();
    check.put(thread1, l2);
    check.put(thread2, l1);
    assertSpecificDeadlock(results[0], check);

    lockManager.clearAllLocksFor(c1);
  }

  public void testDeadLock3() {
    if (true) {
      Banner.warnBanner("DEADLOCK tests DISABLED");
      return;
    }

    // test that includes locks with more than 1 holder

    // contended locks
    LockID l1 = new LockID("1");
    LockID l2 = new LockID("2");

    // uncontended read locks
    LockID l3 = new LockID("3");
    LockID l4 = new LockID("4");
    LockID l5 = new LockID("5");

    ClientID c1 = new ClientID(new ChannelID(1));
    ThreadID s1 = new ThreadID(1);
    ThreadID s2 = new ThreadID(2);

    ServerThreadID thread1 = new ServerThreadID(c1, s1);
    ServerThreadID thread2 = new ServerThreadID(c1, s2);

    lockManager.start();

    // thread1 holds all three read locks, thread2 has 2 of them
    lockManager.requestLock(l3, c1, s1, LockLevel.READ, String.class.getName(), sink);
    lockManager.requestLock(l4, c1, s1, LockLevel.READ, String.class.getName(), sink);
    lockManager.requestLock(l5, c1, s1, LockLevel.READ, String.class.getName(), sink);
    lockManager.requestLock(l3, c1, s2, LockLevel.READ, String.class.getName(), sink);
    lockManager.requestLock(l4, c1, s2, LockLevel.READ, String.class.getName(), sink);

    // thread1 gets lock1
    lockManager.requestLock(l1, c1, s1, LockLevel.WRITE, String.class.getName(), sink);
    // thread2 gets lock2
    lockManager.requestLock(l2, c1, s2, LockLevel.WRITE, String.class.getName(), sink);
    // thread1 trys to get lock2 (blocks)
    lockManager.requestLock(l2, c1, s1, LockLevel.WRITE, String.class.getName(), sink);
    // thread2 trys to get lock1 (blocks)
    lockManager.requestLock(l1, c1, s2, LockLevel.WRITE, String.class.getName(), sink);

    TestDeadlockResults deadlocks = new TestDeadlockResults();
    lockManager.scanForDeadlocks(deadlocks);

    assertEquals(1, deadlocks.chains.size());
    Map check = new HashMap();
    check.put(thread1, l2);
    check.put(thread2, l1);
    assertSpecificDeadlock((DeadlockChain) deadlocks.chains.get(0), check);

    lockManager.clearAllLocksFor(c1);
  }

  public void disableTestUpgradeDeadLock() {
    // Detect deadlock in competing upgrades
    LockID l1 = new LockID("L1");

    ClientID c0 = new ClientID(new ChannelID(0));
    ThreadID s1 = new ThreadID(1);
    ThreadID s2 = new ThreadID(2);

    ServerThreadID thread1 = new ServerThreadID(c0, s1);
    ServerThreadID thread2 = new ServerThreadID(c0, s2);

    lockManager.start();

    // thread1 gets lock1 (R)
    lockManager.requestLock(l1, c0, s1, LockLevel.READ, String.class.getName(), sink);
    // thread2 gets lock1 (R)
    lockManager.requestLock(l1, c0, s2, LockLevel.READ, String.class.getName(), sink);

    // thread1 requests upgrade
    lockManager.requestLock(l1, c0, s1, LockLevel.WRITE, String.class.getName(), sink);
    // thread2 requests upgrade
    lockManager.requestLock(l1, c0, s2, LockLevel.WRITE, String.class.getName(), sink);

    TestDeadlockResults deadlocks = new TestDeadlockResults();
    lockManager.scanForDeadlocks(deadlocks);

    assertEquals(1, deadlocks.chains.size());

    Map check = new HashMap();
    check.put(thread1, l1);
    check.put(thread2, l1);
    assertSpecificDeadlock((DeadlockChain) deadlocks.chains.get(0), check);

    lockManager.clearAllLocksFor(c0);
  }

  public void testLackOfDeadlock() throws InterruptedException {
    if (true) {
      Banner.warnBanner("DEADLOCK tests DISABLED");
      return;
    }

    lockManager.start();
    for (int i = 0; i < 500; i++) {
      internalTestLackofDeadlock(false);
      resetLockManager(true);
      internalTestLackofDeadlock(true);
      resetLockManager(true);
    }
  }

  private void internalTestLackofDeadlock(boolean useRealThreads) throws InterruptedException {
    List threads = new ArrayList();

    for (int t = 0; t < numThreads; t++) {
      NodeID cid = txns[t].getNodeID();
      ThreadID tid = txns[t].getClientThreadID();

      RandomRequest req = new RandomRequest(cid, tid);
      if (useRealThreads) {
        Thread thread = new Thread(req);
        thread.start();
        threads.add(thread);
      } else {
        req.run();
      }
    }

    if (useRealThreads) {
      for (Iterator iter = threads.iterator(); iter.hasNext();) {
        Thread t = (Thread) iter.next();
        t.join();
      }
    }

    TestDeadlockResults results = new TestDeadlockResults();
    lockManager.scanForDeadlocks(results);

    assertEquals(0, results.chains.size());

    for (int i = 0; i < txns.length; i++) {
      lockManager.clearAllLocksFor(txns[i].getNodeID());
    }
  }

  private class RandomRequest implements Runnable {
    private final NodeID   cid;
    private final ThreadID tid;

    public RandomRequest(NodeID cid, ThreadID tid) {
      this.cid = cid;
      this.tid = tid;
    }

    public void run() {
      final int start = random.nextInt(numLocks);
      final int howMany = random.nextInt(numLocks - start);

      for (int i = 0; i < howMany; i++) {
        LockID lock = locks[start + i];
        boolean read = random.nextInt(10) < 8; // 80% reads
        int level = read ? LockLevel.READ : LockLevel.WRITE;
        boolean granted = lockManager.requestLock(lock, cid, tid, level, String.class.getName(), sink);
        if (!granted) {
          break;
        }
      }
    }
  }

  private ServerThreadID[] makeUniqueTxns(int num) {
    ServerThreadID[] rv = new ServerThreadID[num];
    for (int i = 0; i < num; i++) {
      rv[i] = new ServerThreadID(new ClientID(new ChannelID(i)), new ThreadID(i));
    }
    return rv;
  }

  private LockID[] makeUniqueLocks(int num) {
    LockID[] rv = new LockID[num];
    for (int i = 0; i < num; i++) {
      rv[i] = new LockID("lock-" + i);
    }

    return rv;
  }

  private void assertSpecificDeadlock(DeadlockChain chain, Map check) {
    DeadlockChain start = chain;
    do {
      LockID lock = (LockID) check.remove(chain.getWaiter());
      assertEquals(lock, chain.getWaitingOn());
      chain = chain.getNextLink();
    } while (chain != start);

    assertEquals(0, check.size());
  }

  public void testDeadLock2() {
    if (true) {
      Banner.warnBanner("DEADLOCK tests DISABLED");
      return;
    }

    // A slightly more complicated deadlock:
    // -- Thread1 holds lock1, wants lock2
    // -- Thread2 holds lock2, wants lock3
    // -- Thread3 holds lock3, wants lock1

    LockID l1 = new LockID("L1");
    LockID l2 = new LockID("L2");
    LockID l3 = new LockID("L3");
    ClientID c0 = new ClientID(new ChannelID(0));
    ThreadID s1 = new ThreadID(1);
    ThreadID s2 = new ThreadID(2);
    ThreadID s3 = new ThreadID(3);

    ServerThreadID thread1 = new ServerThreadID(c0, s1);
    ServerThreadID thread2 = new ServerThreadID(c0, s2);
    ServerThreadID thread3 = new ServerThreadID(c0, s3);

    lockManager.start();

    // thread1 gets lock1
    lockManager.requestLock(l1, c0, s1, LockLevel.WRITE, String.class.getName(), sink);
    // thread2 gets lock2
    lockManager.requestLock(l2, c0, s2, LockLevel.WRITE, String.class.getName(), sink);
    // thread3 gets lock3
    lockManager.requestLock(l3, c0, s3, LockLevel.WRITE, String.class.getName(), sink);

    // thread1 trys to get lock2 (blocks)
    lockManager.requestLock(l2, c0, s1, LockLevel.WRITE, String.class.getName(), sink);
    // thread2 trys to get lock3 (blocks)
    lockManager.requestLock(l3, c0, s2, LockLevel.WRITE, String.class.getName(), sink);
    // thread3 trys to get lock1 (blocks)
    lockManager.requestLock(l1, c0, s3, LockLevel.WRITE, String.class.getName(), sink);

    TestDeadlockResults deadlocks = new TestDeadlockResults();
    lockManager.scanForDeadlocks(deadlocks);

    assertEquals(1, deadlocks.chains.size());

    Map check = new HashMap();
    check.put(thread1, l2);
    check.put(thread2, l3);
    check.put(thread3, l1);
    assertSpecificDeadlock((DeadlockChain) deadlocks.chains.get(0), check);

    lockManager.clearAllLocksFor(c0);
  }

  private class ShutdownThread extends Thread {
    private final LinkedQueue shutdownSteps;

    private ShutdownThread(LinkedQueue shutdownSteps) {
      this.shutdownSteps = shutdownSteps;
    }

    public void run() {
      try {
        shutdownSteps.put(new Object());
        lockManager.stop();
        shutdownSteps.put(new Object());
      } catch (Exception e) {
        e.printStackTrace();
        fail();
      }
    }
  }

  private static class TestDeadlockResults implements DeadlockResults {
    final List chains = new ArrayList();

    public void foundDeadlock(DeadlockChain chain) {
      chains.add(chain);
    }
  }

}
