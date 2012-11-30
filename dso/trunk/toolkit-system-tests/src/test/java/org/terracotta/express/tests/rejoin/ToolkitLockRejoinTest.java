/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.express.tests.rejoin;

import org.terracotta.express.tests.util.ClusteredStringBuilder;
import org.terracotta.express.tests.util.ClusteredStringBuilderFactory;
import org.terracotta.express.tests.util.ClusteredStringBuilderFactoryImpl;
import org.terracotta.toolkit.Toolkit;
import org.terracotta.toolkit.concurrent.ToolkitBarrier;
import org.terracotta.toolkit.concurrent.locks.ToolkitLock;
import org.terracotta.toolkit.concurrent.locks.ToolkitReadWriteLock;
import org.terracotta.toolkit.internal.ToolkitInternal;
import org.terracotta.toolkit.rejoin.RejoinException;
import org.terracotta.toolkit.store.ToolkitStore;

import com.tc.test.config.model.TestConfig;
import com.tc.test.jmx.TestHandlerMBean;

import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

public class ToolkitLockRejoinTest extends AbstractToolkitRejoinTest {

  public ToolkitLockRejoinTest(TestConfig testConfig) {
    super(testConfig, ToolkitLockRejoinTestClient.class, ToolkitLockRejoinTestClient.class);
    testConfig.getL2Config().setRestartable(false);
  }

  public static class ToolkitLockRejoinTestClient extends AbstractToolkitRejoinTestClient {
    private final int lockUnlockCount = 10;
    private static final int              CLIENT_COUNT    = 2;
    private static final int              LOCK_COUNT      = 5;
    private ClusteredStringBuilderFactory csbFactory;

    public ToolkitLockRejoinTestClient(String[] args) {
      super(args);
    }

    @Override
    protected void doRejoinTest(TestHandlerMBean testHandlerMBean) throws Throwable {
      ToolkitInternal toolkit = createRejoinToolkit();
      csbFactory = new ClusteredStringBuilderFactoryImpl(toolkit);
      ToolkitBarrier lockBarrier = toolkit.getBarrier("lockBarrier", CLIENT_COUNT);
      int index = lockBarrier.await();
      doDebug("client " + index + " starting.. ");

      // test before rejoin
      testLock(toolkit, index);
      testRWLock(toolkit, index);
      doDebug("client " + index + " is going to do rejoin");
      lockBarrier.await();
      
      // test during rejoin
      testLockWithRejoin(testHandlerMBean, toolkit, index);

      doDebug("client " + index + " is running after rejoin");
      lockBarrier.await();
      // test after rejoin
      testLock(toolkit, index);
      testRWLock(toolkit, index);
    }

    private void testLockWithRejoin(TestHandlerMBean testHandlerMBean, ToolkitInternal toolkit, int index)
        throws Throwable {
      ToolkitLock lock = toolkit.getLock("testLock");
      ToolkitBarrier testBarrier = toolkit.getBarrier("testBarrier", CLIENT_COUNT);

      if (index == 0) {
        lock.lock();
        try {
          startRejoinAndWaitUntilPassiveStandBy(testHandlerMBean, toolkit);
        } finally {
          try {
            lock.unlock();
          } catch (RejoinException expected) {
            // ignored
          }
        }
        testBarrier.await();
        doLockUnlock(lock);
      } else {
        waitUntilRejoinCompleted();
        testBarrier.await();
        doLockUnlock(lock);
      }

      testBarrier.await();

      for (int i = 0; i < LOCK_COUNT; i++) {
        ToolkitReadWriteLock rwLock = toolkit.getReadWriteLock("testRWLock" + i);
        if (index == 0) {
          rwLock.writeLock().lock();
          try {
            startRejoinAndWaitUntilPassiveStandBy(testHandlerMBean, toolkit);
          } finally {
            try {
              rwLock.writeLock().unlock();
            } catch (RejoinException expected) {
              // ignored
            }
          }
          testBarrier.await();
          doLockUnlock(rwLock.writeLock());
        } else {
          waitUntilRejoinCompleted();
          testBarrier.await();
          doLockUnlock(rwLock.writeLock());
        }
      }

      testBarrier.await();

      for (int i = 0; i < LOCK_COUNT; i++) {
        ToolkitReadWriteLock rwLock = toolkit.getReadWriteLock("testRWLock" + i);
        if (index == 0) {
          rwLock.readLock().lock();
          try {
            startRejoinAndWaitUntilPassiveStandBy(testHandlerMBean, toolkit);
          } finally {
            try {
              rwLock.readLock().unlock();
            } catch (RejoinException expected) {
              // ignored
            }
          }
          testBarrier.await();
          doLockUnlock(rwLock.readLock());
        } else {
          waitUntilRejoinCompleted();
          testBarrier.await();
          doLockUnlock(rwLock.readLock());
        }
      }
      testBarrier.await();
    }

    private void doLockUnlock(ToolkitLock lock) {
      lock.lock();
      try {
        // do nothing
      } finally {
        lock.unlock();
      }
    }

    private void testLock(Toolkit toolkit, int index) throws Throwable {
      ToolkitLock lock = toolkit.getLock("testLock");
      ToolkitBarrier testBarrier = toolkit.getBarrier("testBarrier", CLIENT_COUNT);


      // testing newCondition();
      try {
        lock.newCondition();
        Assert.fail("newCondition() should have failed");
      } catch (UnsupportedOperationException expected) {
        // ignored
      }
      // testing unlock()
      try {
        lock.unlock();
        Assert.fail("unlock() should have failed without lock()");
      } catch (IllegalMonitorStateException expected) {
        // ignored
      }
      testBarrier.await();

      if (index == 0) {
        lockTimes(lock);
        try {
          lock.getCondition().await();
        } finally {
          unlockTimes(lock);
        }
      } else {
        TimeUnit.SECONDS.sleep(10L);
        lockTimes(lock);
        try {
          lock.getCondition().signalAll();
        } finally {
          unlockTimes(lock);
        }
      }
      testBarrier.await();

      // testing lock boundaries
      ToolkitStore<String, String> toolkitStore = toolkit.getStore("toolkitStore", String.class);
      String value = "dummyValue";
      if (index == 0) {
        lock.lock();
        try {
          toolkitStore.put(lock.getName(), value);
          testBarrier.await();
        } finally {
          lock.unlock();
        }
      } else {
        testBarrier.await();
        lock.lock();
        Assert.assertEquals(value, toolkitStore.get(lock.getName()));
        try {
          toolkitStore.replace(lock.getName(), value + value);
        } finally {
          lock.unlock();
        }
        Assert.assertEquals(value + value, toolkitStore.get(lock.getName()));
      }
      testBarrier.await();
    }

    private void testRWLock(Toolkit toolkit, int index) throws Throwable {
      testReadLockUnlock(toolkit, index);
      testWriteLockUnlock(toolkit, index);
      testTryReadLockUnlock(toolkit, index);
      testTryWriteLockUnlock(toolkit, index);
    }

    private void testReadLockUnlock(Toolkit toolkit, int index) throws Throwable {
      ToolkitBarrier testBarrier = toolkit.getBarrier("testBarrier", CLIENT_COUNT);
      // testing getCondition();
      try {
        toolkit.getReadWriteLock("sampleRWLock").readLock().getCondition();
        Assert.fail("getCondition() should have failed with readLock");
      } catch (UnsupportedOperationException expected) {
        // ignored
      }
      for (int i = 0; i < LOCK_COUNT; i++) {
        ToolkitReadWriteLock lock = toolkit.getReadWriteLock("testRWLock" + i);
        ClusteredStringBuilder bucket = csbFactory.getClusteredStringBuilder("basicReadLockUnlockBucket" + i);
        if (index == 0) {
          lock.readLock().lock();
          try {
            bucket.append("r");
            testBarrier.await();
          } finally {
            lock.readLock().unlock();
          }
          testBarrier.await();
          String str = bucket.toString();
          Assert.assertEquals("rr", str);

          System.out.println("basicReadLockUnlock " + i + " passed. String = " + str);
          testBarrier.await();
        } else {
          lock.readLock().lock();
          try {
            bucket.append("r");
            testBarrier.await();
          } finally {
            lock.readLock().unlock();
          }
          testBarrier.await();
          String str = bucket.toString();
          Assert.assertEquals("rr", str);

          System.out.println("basicReadLockUnlock " + i + " passed. String = " + str);
          testBarrier.await();
        }
      }
    }

    private void testWriteLockUnlock(Toolkit toolkit, int index) throws Throwable {
      ToolkitBarrier testBarrier = toolkit.getBarrier("testBarrier", CLIENT_COUNT);
      for (int i = 0; i < LOCK_COUNT; i++) {
        ToolkitReadWriteLock lock = toolkit.getReadWriteLock("testRWLock" + i);
        ClusteredStringBuilder bucket = csbFactory.getClusteredStringBuilder("basicWriteLockUnlock" + i);
        if (index == 0) {
          System.err.println(" lock " + lock.getClass().getName());
          lock.writeLock().lock();
          try {
            testBarrier.await();
            bucket.append("l1");
          } finally {
            lock.writeLock().unlock();
          }
          testBarrier.await();

          String str = bucket.toString();
          Assert.assertEquals("l1l2", str);

          System.out.println("basicWriteLockUnlock " + i + " passed. String = " + str);
          testBarrier.await();
        } else {
          testBarrier.await();
          lock.writeLock().lock();
          try {
            bucket.append("l2");
          } finally {
            lock.writeLock().unlock();
          }
          testBarrier.await();

          String str = bucket.toString();
          Assert.assertEquals("l1l2", str);

          System.out.println("basicWriteLockUnlock " + i + " passed. String = " + str);
          testBarrier.await();
        }
      }
    }

    private void testTryReadLockUnlock(Toolkit toolkit, int index) throws Throwable {
      ToolkitBarrier testBarrier = toolkit.getBarrier("testBarrier", CLIENT_COUNT);
      for (int i = 0; i < LOCK_COUNT; i++) {
        ToolkitReadWriteLock lock = toolkit.getReadWriteLock("testRWLock" + i);
        ClusteredStringBuilder bucket = csbFactory.getClusteredStringBuilder("basicTryReadLockUnlockBucket" + i);
        if (index == 0) {
          lock.readLock().lock();
          testBarrier.await();
          try {
            bucket.append("r");
            testBarrier.await();
          } finally {
            lock.readLock().unlock();
          }
          testBarrier.await();
          String str = bucket.toString();
          Assert.assertEquals("rr", str);

          System.out.println("basicTryReadLockUnlock " + i + " passed. String = " + str);
          testBarrier.await();
        } else {
          testBarrier.await();
          boolean acquired = lock.readLock().tryLock();
          Assert.assertTrue(acquired);
          try {
            bucket.append("r");
            testBarrier.await();
          } finally {
            lock.readLock().unlock();
          }
          testBarrier.await();
          String str = bucket.toString();
          Assert.assertEquals("rr", str);

          System.out.println("basicTryReadLockUnlock " + i + " passed. String = " + str);
          testBarrier.await();
        }
      }
    }

    private void testTryWriteLockUnlock(Toolkit toolkit, int index) throws Throwable {
      ToolkitBarrier testBarrier = toolkit.getBarrier("testBarrier", CLIENT_COUNT);
      for (int i = 0; i < LOCK_COUNT; i++) {
        ToolkitReadWriteLock lock = toolkit.getReadWriteLock("testRWLock" + i);
        ClusteredStringBuilder bucket = csbFactory.getClusteredStringBuilder("basicTryWriteLockUnlockBucket" + i);
        if (index == 0) {
          lock.writeLock().lock();
          testBarrier.await(); // 1
          try {
            bucket.append("r");
            testBarrier.await(); // 2
          } finally {
            lock.writeLock().unlock();
          }

          testBarrier.await(); // 3
          testBarrier.await(); // 4

          String str = bucket.toString();
          Assert.assertEquals("rr", str);

          System.out.println("basicTryWriteLockUnlock " + i + " passed. String = " + str);
          testBarrier.await(); // 5

        } else {
          testBarrier.await(); // 1
          boolean acquired = lock.writeLock().tryLock();
          Assert.assertFalse(acquired);

          testBarrier.await(); // 2
          testBarrier.await(); // 3
          acquired = lock.writeLock().tryLock();
          Assert.assertTrue(acquired);

          try {
            bucket.append("r");
          } finally {
            lock.writeLock().unlock();
          }
          testBarrier.await(); // 4

          String str = bucket.toString();
          Assert.assertEquals("rr", str);

          System.out.println("basicTryWriteLockUnlock " + i + " passed. String = " + str);
          testBarrier.await(); // 5
        }
      }
    }

    private void lockTimes(ToolkitLock lock) {
      for (int i = 0; i < lockUnlockCount; ++i) {
        lock.lock();
      }
    }

    private void unlockTimes(ToolkitLock lock) {
      for (int i = 0; i < lockUnlockCount; ++i) {
        lock.unlock();
      }
    }

  }
}
