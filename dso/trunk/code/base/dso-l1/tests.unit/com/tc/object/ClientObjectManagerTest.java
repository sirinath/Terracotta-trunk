/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object;

import EDU.oswego.cs.dl.util.concurrent.BrokenBarrierException;
import EDU.oswego.cs.dl.util.concurrent.CyclicBarrier;
import EDU.oswego.cs.dl.util.concurrent.SynchronizedBoolean;

import com.tc.exception.ImplementMe;
import com.tc.exception.TCObjectNotFoundException;
import com.tc.net.protocol.tcm.TestChannelIDProvider;
import com.tc.object.TestClassFactory.MockTCClass;
import com.tc.object.TestClassFactory.MockTCField;
import com.tc.object.bytecode.Manageable;
import com.tc.object.bytecode.MockClassProvider;
import com.tc.object.bytecode.TransparentAccess;
import com.tc.object.cache.EvictionPolicy;
import com.tc.object.cache.NullCache;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAException;
import com.tc.object.field.TCField;
import com.tc.object.idprovider.api.ObjectIDProvider;
import com.tc.object.loaders.ClassProvider;
import com.tc.object.logging.NullRuntimeLogger;
import com.tc.object.logging.RuntimeLogger;
import com.tc.object.tx.ClientTransactionManager;
import com.tc.object.tx.MockTransactionManager;
import com.tc.util.Assert;
import com.tc.util.Counter;
import com.tc.util.concurrent.ThreadUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ClientObjectManagerTest extends BaseDSOTestCase {
  private ClientObjectManager     mgr;
  private TestRemoteObjectManager remoteObjectManager;
  private DSOClientConfigHelper   clientConfiguration;
  private ObjectIDProvider        idProvider;
  private EvictionPolicy          cache;
  private RuntimeLogger           runtimeLogger;
  private ClassProvider           classProvider;
  private TCClassFactory          classFactory;
  private TestObjectFactory       objectFactory;
  private String                  rootName;
  private Object                  object;
  private ObjectID                objectID;
  private MockTCObject            tcObject;
  private CyclicBarrier           mutualRefBarrier;

  @Override
  public void setUp() throws Exception {
    this.remoteObjectManager = new TestRemoteObjectManager();
    this.classProvider = new MockClassProvider();
    this.clientConfiguration = configHelper();
    this.classFactory = new TestClassFactory();
    this.objectFactory = new TestObjectFactory();
    this.cache = new NullCache();
    this.runtimeLogger = new NullRuntimeLogger();

    this.rootName = "myRoot";
    this.object = new Object();
    this.objectID = new ObjectID(1);
    this.tcObject = new MockTCObject(this.objectID, this.object);
    this.objectFactory.peerObject = this.object;
    this.objectFactory.tcObject = this.tcObject;

    this.mgr = new ClientObjectManagerImpl(this.remoteObjectManager, this.clientConfiguration, this.idProvider,
                                           this.cache, this.runtimeLogger,
                                           new ClientIDProviderImpl(new TestChannelIDProvider()), this.classProvider,
                                           this.classFactory, this.objectFactory,
                                           new PortabilityImpl(this.clientConfiguration), null, null);
    this.mgr.setTransactionManager(new MockTransactionManager());
  }

  public void testObjectNotFoundConcurrentLookup() throws Exception {
    final ObjectID id = new ObjectID(1);
    final List errors = Collections.synchronizedList(new ArrayList());

    Runnable lookup = new Runnable() {
      public void run() {
        try {
          ClientObjectManagerTest.this.mgr.lookup(id);
        } catch (Throwable t) {
          System.err.println("got exception: " + t.getClass().getName());
          errors.add(t);
        }
      }
    };

    Thread t1 = new Thread(lookup);
    t1.start();
    Thread t2 = new Thread(lookup);
    t2.start();

    ThreadUtil.reallySleep(5000);

    this.remoteObjectManager.retrieveResults.put(TestRemoteObjectManager.THROW_NOT_FOUND);
    this.remoteObjectManager.retrieveResults.put(TestRemoteObjectManager.THROW_NOT_FOUND);

    t1.join();
    t2.join();

    assertEquals(2, errors.size());
    assertEquals(TCObjectNotFoundException.class, errors.remove(0).getClass());
    assertEquals(TCObjectNotFoundException.class, errors.remove(0).getClass());
  }

  public void testMutualReferenceLookup() {
    this.mutualRefBarrier = new CyclicBarrier(2);

    this.remoteObjectManager = new TestRemoteObjectManager() {

      @Override
      public DNA retrieve(ObjectID id) {
        try {

          ClientObjectManagerTest.this.mutualRefBarrier.barrier();
        } catch (BrokenBarrierException e) {
          throw new AssertionError(e);
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
        return new TestDNA(id);
      }
    };

    // re-init manager
    TestMutualReferenceObjectFactory testMutualReferenceObjectFactory = new TestMutualReferenceObjectFactory();
    ClientObjectManagerImpl clientObjectManager = new ClientObjectManagerImpl(
                                                                              this.remoteObjectManager,
                                                                              this.clientConfiguration,
                                                                              this.idProvider,
                                                                              this.cache,
                                                                              this.runtimeLogger,
                                                                              new ClientIDProviderImpl(
                                                                                                       new TestChannelIDProvider()),
                                                                              this.classProvider,
                                                                              this.classFactory,
                                                                              testMutualReferenceObjectFactory,
                                                                              new PortabilityImpl(
                                                                                                  this.clientConfiguration),
                                                                              null, null);
    this.mgr = clientObjectManager;
    MockTransactionManager mockTransactionManager = new MockTransactionManager();
    this.mgr.setTransactionManager(mockTransactionManager);
    testMutualReferenceObjectFactory.setObjectManager(this.mgr);

    // run the threads now..
    ObjectID objectID1 = new ObjectID(1);
    ObjectID objectID2 = new ObjectID(2);
    ExceptionHolder exceptionHolder1 = new ExceptionHolder();
    ExceptionHolder exceptionHolder2 = new ExceptionHolder();

    LookupThread lookupThread1 = new LookupThread(objectID1, this.mgr, mockTransactionManager, exceptionHolder1);
    LookupThread lookupThread2 = new LookupThread(objectID2, this.mgr, mockTransactionManager, exceptionHolder2);
    //
    Thread t1 = new Thread(lookupThread1);
    t1.start();
    Thread t2 = new Thread(lookupThread2);
    t2.start();
    try {
      t1.join();
      t2.join();

    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }

    if (exceptionHolder1.getExceptionOccurred().get()) { throw new AssertionError(exceptionHolder1.getThreadException()); }

    if (exceptionHolder2.getExceptionOccurred().get()) { throw new AssertionError(exceptionHolder2.getThreadException()); }

    assertEquals(mockTransactionManager.getLoggingCounter().get(), 0);
    assertEquals(clientObjectManager.getObjectLatchStateMap().size(), 0);

  }

  private static final class LookupThread implements Runnable {

    private final ObjectID                 oid;
    private final ClientObjectManager      clientObjectManager;
    private final ClientTransactionManager clientTransactionManager;
    private final ExceptionHolder          exceptionHolder;

    public LookupThread(ObjectID oid, ClientObjectManager clientObjectManager,
                        ClientTransactionManager clientTransactionManager, ExceptionHolder exceptionHolder) {
      this.oid = oid;
      this.clientObjectManager = clientObjectManager;
      this.clientTransactionManager = clientTransactionManager;
      this.exceptionHolder = exceptionHolder;
    }

    public void run() {
      try {
        assertNotNull(this.clientObjectManager);
        assertNotNull(this.clientTransactionManager);
        assertNotNull(this.oid);
        assertFalse(this.clientTransactionManager.isTransactionLoggingDisabled());
        TestObject testObject = (TestObject) this.clientObjectManager.lookupObject(this.oid);
        assertNotNull(testObject);
        assertNotNull(testObject.object);
        assertFalse(this.clientTransactionManager.isTransactionLoggingDisabled());
      } catch (Exception e) {
        this.exceptionHolder.getExceptionOccurred().set(true);
        this.exceptionHolder.setThreadException(e);
      }
    }
  }

  private static final class ExceptionHolder {
    private final SynchronizedBoolean exceptionOccurred = new SynchronizedBoolean(false);
    private Exception                 threadException;

    public Exception getThreadException() {
      return this.threadException;
    }

    public void setThreadException(Exception threadException) {
      this.threadException = threadException;
    }

    public SynchronizedBoolean getExceptionOccurred() {
      return this.exceptionOccurred;
    }

  }

  public void testExceptionDuringHydrateClearsState() throws Exception {
    RuntimeException expect = new RuntimeException();
    this.tcObject.setHydrateException(expect);

    TestDNA dna = newEmptyDNA();
    prepareObjectLookupResults(dna);

    try {
      this.mgr.lookup(this.objectID);
      fail("no exception");
    } catch (Exception e) {
      if (!(e == expect || e.getCause() == expect)) {
        fail(e);
      }
    }

    dna = newEmptyDNA();
    prepareObjectLookupResults(dna);

    try {
      this.mgr.lookup(this.objectID);
      fail("no exception");
    } catch (Exception e) {
      if (!(e == expect || e.getCause() == expect)) {
        fail(e);
      }
    }
  }

  /**
   * Test to make sure that a root request that is blocked waiting for a server response doesn't block reconnect.
   */
  public void testLookupOrCreateRootDoesntBlockReconnect() throws Exception {
    // prepare a successful object lookup
    TestDNA dna = newEmptyDNA();
    prepareObjectLookupResults(dna);

    // prepare the lookup thread
    CyclicBarrier barrier = new CyclicBarrier(1);
    LookupRootAgent lookup1 = new LookupRootAgent(barrier, this.mgr, this.rootName, this.object);

    // start the lookup
    new Thread(lookup1).start();
    // make sure the first caller has called down into the remote object manager
    this.remoteObjectManager.retrieveRootIDCalls.take();
    assertNull(this.remoteObjectManager.retrieveRootIDCalls.poll(0));
  }

  /**
   * Test to make sure that concurrent root requests only result in a single lookup to the server.
   */
  public void testLookupOrCreateRootConcurrently() throws Exception {

    // prepare a successful object lookup
    TestDNA dna = newEmptyDNA();
    prepareObjectLookupResults(dna);

    // prepare the lookup threads.
    CyclicBarrier barrier = new CyclicBarrier(3);
    LookupRootAgent lookup1 = new LookupRootAgent(barrier, this.mgr, this.rootName, this.object);
    LookupRootAgent lookup2 = new LookupRootAgent(barrier, this.mgr, this.rootName, this.object);

    // start the first lookup
    new Thread(lookup1).start();
    // make sure the first caller has called down into the remote object manager.
    this.remoteObjectManager.retrieveRootIDCalls.take();
    assertNull(this.remoteObjectManager.retrieveRootIDCalls.poll(0));

    // now start another lookup and make sure that it doesn't call down into the remote object manager.
    new Thread(lookup2).start();
    ThreadUtil.reallySleep(5000);
    assertNull(this.remoteObjectManager.retrieveRootIDCalls.poll(0));

    // allow the first lookup to proceed.
    this.remoteObjectManager.retrieveRootIDResults.put(this.objectID);

    // make sure both lookups are complete.
    barrier.barrier();

    assertTrue(lookup1.success() && lookup2.success());

    // They should both have the same result
    assertEquals(lookup1, lookup2);

    // but, the remote object manager retrieveRootID() should only have been called the first time.
    assertNull(this.remoteObjectManager.retrieveRootIDCalls.poll(0));
  }

  private TestDNA newEmptyDNA() {
    TestDNA dna = new TestDNA();
    dna.objectID = this.objectID;
    dna.arraySize = 0;
    return dna;
  }

  private void prepareObjectLookupResults(TestDNA dna) {
    this.remoteObjectManager.retrieveResults.put(dna);
  }

  private static final class LookupRootAgent implements Runnable {
    private final ClientObjectManager manager;
    private final String              rootName;
    private final Object              object;
    public Object                     result;
    public Throwable                  exception;
    private final CyclicBarrier       barrier;

    private LookupRootAgent(CyclicBarrier barrier, ClientObjectManager manager, String rootName, Object object) {
      this.barrier = barrier;
      this.manager = manager;
      this.rootName = rootName;
      this.object = object;
    }

    public boolean success() {
      return this.exception == null;
    }

    public void run() {
      try {
        this.result = this.manager.lookupOrCreateRoot(this.rootName, this.object);
      } catch (Throwable t) {
        t.printStackTrace();
        this.exception = t;
      } finally {
        try {
          this.barrier.barrier();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof LookupRootAgent) {
        LookupRootAgent cmp = (LookupRootAgent) o;
        if (this.result == null) { return cmp.result == null; }
        return this.result.equals(cmp.result);
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      throw new RuntimeException("Don't ask me that.");
    }

  }

  private static class TestDNA implements DNA {

    public ObjectID objectID;
    public ObjectID parentObjectID = ObjectID.NULL_ID;
    public int      arraySize;

    public TestDNA() {
      // do nothing
    }

    public TestDNA(ObjectID objectID) {
      this.objectID = objectID;
    }

    public long getVersion() {
      throw new ImplementMe();
    }

    public boolean hasLength() {
      throw new ImplementMe();
    }

    public int getArraySize() {
      return this.arraySize;
    }

    public String getTypeName() {
      return getClass().getName();
    }

    public ObjectID getObjectID() throws DNAException {
      return this.objectID;
    }

    public ObjectID getParentObjectID() throws DNAException {
      return this.parentObjectID;
    }

    public DNACursor getCursor() {
      throw new ImplementMe();
    }

    public String getDefiningLoaderDescription() {
      return "mock";
    }

    public boolean isDelta() {
      return false;
    }

  }

  private static class TestMutualReferenceObjectFactory implements TCObjectFactory {

    private ClientObjectManager clientObjectManager;
    private final ThreadLocal   localDepthCounter = new ThreadLocal() {

                                                    @Override
                                                    protected synchronized Object initialValue() {
                                                      return new Counter();
                                                    }

                                                  };

    public void setObjectManager(ClientObjectManager objectManager) {
      this.clientObjectManager = objectManager;
    }

    public Object getNewPeerObject(TCClass type, Object parent) throws IllegalArgumentException, SecurityException {
      return new TestObject();
    }

    public Object getNewArrayInstance(TCClass type, int size) {
      throw new ImplementMe();
    }

    public Object getNewPeerObject(TCClass type) throws IllegalArgumentException, SecurityException {
      return new TestObject();
    }

    public Object getNewPeerObject(TCClass type, DNA dna) {
      return new TestObject();
    }

    private Counter getLocalDepthCounter() {
      return (Counter) this.localDepthCounter.get();
    }

    public TCObject getNewInstance(ObjectID id, Object peer, Class clazz, boolean isNew) {
      throw new ImplementMe();
    }

    public TCObject getNewInstance(ObjectID id, Class clazz, boolean isNew) {
      TCObjectPhysical tcObj = null;
      if (id.toLong() == 1) {
        if (getLocalDepthCounter().get() == 0) {
          TCField[] tcFields = new TCField[] { new MockTCField("object") };
          TCClass tcClass = new MockTCClass(this.clientObjectManager, true, true, true, tcFields);
          tcObj = new TCObjectPhysical(id, null, tcClass, isNew);
          tcObj.setReference("object", new ObjectID(2));
          getLocalDepthCounter().increment();
        } else {
          TCField[] tcFields = new TCField[] { new MockTCField("object") };
          TCClass tcClass = new MockTCClass(this.clientObjectManager, true, true, true, tcFields);
          tcObj = new TCObjectPhysical(id, null, tcClass, isNew);
          getLocalDepthCounter().increment();
        }
      } else if (id.toLong() == 2) {
        if (getLocalDepthCounter().get() == 0) {
          TCField[] tcFields = new TCField[] { new MockTCField("object") };
          TCClass tcClass = new MockTCClass(this.clientObjectManager, true, true, true, tcFields);
          tcObj = new TCObjectPhysical(id, null, tcClass, isNew);
          tcObj.setReference("object", new ObjectID(1));
          getLocalDepthCounter().increment();
        } else {
          TCField[] tcFields = new TCField[] { new MockTCField("object") };
          TCClass tcClass = new MockTCClass(this.clientObjectManager, true, true, true, tcFields);
          tcObj = new TCObjectPhysical(id, null, tcClass, isNew);
          getLocalDepthCounter().increment();
        }
      }

      return tcObj;
    }

  }

  private static class TestObject implements TransparentAccess, Manageable {
    public TestObject object;
    private TCObject  tcObject;

    public void __tc_getallfields(Map map) {
      throw new ImplementMe();

    }

    public Object __tc_getmanagedfield(String name) {
      throw new ImplementMe();
    }

    public void __tc_setfield(String name, Object value) {
      if ("object".equals(name)) {
        this.object = (TestObject) value;
      }
    }

    public void __tc_setmanagedfield(String name, Object value) {
      throw new ImplementMe();

    }

    public boolean __tc_isManaged() {
      return this.tcObject == null ? false : true;
    }

    public void __tc_managed(TCObject t) {
      this.tcObject = t;
    }

    public TCObject __tc_managed() {
      return this.tcObject;
    }
  }

  private static class StupidTestObject implements TransparentAccess {
    private static final Random rndm = new Random();

    @SuppressWarnings("unused")
    private StupidTestObject    object;

    public void __tc_getallfields(Map map) {
      throw new ImplementMe();

    }

    public Object __tc_getmanagedfield(String name) {
      throw new ImplementMe();
    }

    public void __tc_setfield(String name, Object value) {
      if ("object".equals(name)) {
        this.object = (StupidTestObject) value;
      }
    }

    public void __tc_setmanagedfield(String name, Object value) {
      throw new ImplementMe();
    }

    @Override
    public boolean equals(Object o) {
      return rndm.nextBoolean();
    }

    @Override
    public int hashCode() {
      return rndm.nextInt();
    }
  }

  public void testSharedObjectWithAbsurdHashCodeEqualsBehavior() throws Exception {
    this.object = new StupidTestObject();
    this.objectID = new ObjectID(1);
    this.tcObject = new MockTCObject(this.objectID, this.object);
    this.objectFactory.peerObject = this.object;
    this.objectFactory.tcObject = this.tcObject;

    TestDNA dna = newEmptyDNA();
    prepareObjectLookupResults(dna);

    TCObject tco = this.mgr.lookup(this.objectID);

    for (int i = 0; i < 100; i++) {
      Assert.assertSame(tco.getObjectID(), this.mgr.lookupExistingObjectID(this.object));
    }
  }
}
