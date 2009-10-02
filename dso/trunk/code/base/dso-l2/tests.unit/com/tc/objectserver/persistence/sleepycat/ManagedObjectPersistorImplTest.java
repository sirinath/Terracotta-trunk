/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.sleepycat;

import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.tc.async.impl.MockSink;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.ObjectID;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAWriter;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.objectserver.core.impl.TestManagedObject;
import com.tc.objectserver.impl.PersistentManagedObjectStore;
import com.tc.objectserver.managedobject.AbstractManagedObjectState;
import com.tc.objectserver.managedobject.BackReferences;
import com.tc.objectserver.managedobject.ManagedObjectTraverser;
import com.tc.objectserver.mgmt.ManagedObjectFacade;
import com.tc.objectserver.mgmt.ObjectStatsRecorder;
import com.tc.objectserver.persistence.api.PersistenceTransaction;
import com.tc.objectserver.persistence.api.PersistenceTransactionProvider;
import com.tc.objectserver.persistence.impl.TestMutableSequence;
import com.tc.objectserver.persistence.sleepycat.FastObjectIDManagerImpl.StoppedFlag;
import com.tc.properties.TCProperties;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.test.TCTestCase;
import com.tc.util.SyncObjectIdSet;
import com.tc.util.SyncObjectIdSetImpl;

import java.io.File;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class ManagedObjectPersistorImplTest extends TCTestCase {
  private static final TCLogger             logger = TCLogging.getTestingLogger(ManagedObjectPersistorImplTest.class);
  private ManagedObjectPersistorImpl        managedObjectPersistor;
  private PersistentManagedObjectStore      objectStore;
  private PersistenceTransactionProvider    persistenceTransactionProvider;
  private TestSleepycatCollectionsPersistor testSleepycatCollectionsPersistor;
  private DBEnvironment                     env;
  private FastObjectIDManagerImpl           oidManager;

  public ManagedObjectPersistorImplTest() {
    //
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    // test only with Oid fastLoad enabled
    TCProperties tcProps = TCPropertiesImpl.getProperties();
    tcProps.setProperty(TCPropertiesConsts.L2_OBJECTMANAGER_LOADOBJECTID_FASTLOAD, "true");
    assertTrue(TCPropertiesImpl.getProperties().getBoolean(TCPropertiesConsts.L2_OBJECTMANAGER_LOADOBJECTID_FASTLOAD));
    boolean paranoid = true;
    env = newDBEnvironment(paranoid);
    env.open();
    persistenceTransactionProvider = new SleepycatPersistenceTransactionProvider(env.getEnvironment());
    CursorConfig rootDBCursorConfig = new CursorConfig();
    SleepycatCollectionFactory sleepycatCollectionFactory = new SleepycatCollectionFactory();
    testSleepycatCollectionsPersistor = new TestSleepycatCollectionsPersistor(logger, env.getMapsDatabase(),
                                                                              sleepycatCollectionFactory);
    managedObjectPersistor = new ManagedObjectPersistorImpl(logger, env.getClassCatalogWrapper().getClassCatalog(),
                                                            new SleepycatSerializationAdapterFactory(), env,
                                                            new TestMutableSequence(), env.getRootDatabase(),
                                                            rootDBCursorConfig, persistenceTransactionProvider,
                                                            testSleepycatCollectionsPersistor, env.isParanoidMode(),
                                                            new ObjectStatsRecorder());
    objectStore = new PersistentManagedObjectStore(managedObjectPersistor, new MockSink());
    oidManager = (FastObjectIDManagerImpl) managedObjectPersistor.getOibjectIDManager();
  }

  @Override
  protected void tearDown() throws Exception {
    oidManager.stopCheckpointRunner();
    env.close();
    super.tearDown();
  }

  @Override
  protected boolean cleanTempDir() {
    return false;
  }

  private DBEnvironment newDBEnvironment(boolean paranoid) throws Exception {
    File dbHome;
    int count = 0;
    do {
      dbHome = new File(this.getTempDirectory(), getClass().getName() + "db" + (++count));
    } while (dbHome.exists());
    dbHome.mkdir();
    assertTrue(dbHome.exists());
    assertTrue(dbHome.isDirectory());
    System.out.println("DB Home: " + dbHome);
    return new DBEnvironment(paranoid, dbHome);
  }

  private Collection createRandomObjects(int num, boolean withPersistentCollectionState) {
    Random r = new Random();
    HashSet objects = new HashSet(num);
    HashSet ids = new HashSet(num);
    for (int i = 0; i < num; i++) {
      long id = (long) r.nextInt(num * 10) + 1;
      if (ids.add(new Long(id))) {
        ManagedObject mo = new TestPersistentStateManagedObject(new ObjectID(id), new ArrayList<ObjectID>(),
                                                                withPersistentCollectionState);
        objects.add(mo);
        objectStore.addNewObject(mo);
      }
    }
    logger.info("Test with " + objects.size() + " objects");
    return (objects);
  }
  
  private SyncObjectIdSet getAllObjectIDs() {
    SyncObjectIdSet rv = new SyncObjectIdSetImpl();
    rv.startPopulating();
    Thread t = new Thread(this.oidManager.getObjectIDReader(rv), "ObjectIdReaderThread");
    t.setDaemon(true);
    t.start();
    try {
      t.join();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    return rv;
  }
  
  private SyncObjectIdSet getAllMapsObjectIDs() {
    SyncObjectIdSet rv = new SyncObjectIdSetImpl();
    rv.startPopulating();
    Thread t = new Thread(this.oidManager.getMapsObjectIDReader(rv), "ObjectIdReaderThread");
    t.setDaemon(true);
    t.start();
    try {
      t.join();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    return rv;
  }

  private void verify(Collection objects) {
    // verify an in-memory bit correspond to an object ID
    HashSet originalIds = new HashSet();
    for (Iterator i = objects.iterator(); i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      originalIds.add(mo.getID());
    }
    
    Collection inMemoryIds = getAllObjectIDs();
    assertTrue("Wrong bits in memory were set", originalIds.containsAll(inMemoryIds));

    // verify on disk object IDs
    SyncObjectIdSet idSet = managedObjectPersistor.getAllObjectIDs();
    idSet.snapshot(); // blocked while reading from disk
    assertTrue("Wrong object IDs on disk", idSet.containsAll(inMemoryIds));
    assertTrue("Wrong object IDs on disk", inMemoryIds.containsAll(idSet));
  }

  private void verifyState(Collection oidSet, Collection objects) {
    for (Iterator i = objects.iterator(); i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      assertTrue("PersistentCollectionMap missing " + mo.getID(), oidSet.contains((mo.getID())));
    }
  }

  public void testOidBitsArraySave() throws Exception {
    // wait for background retrieving persistent data
    objectStore.getAllObjectIDs();

    // publish data
    Collection objects = createRandomObjects(15050, false);
    PersistenceTransaction ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.saveAllObjects(ptx, objects);
    } finally {
      ptx.commit();
    }

    runCheckpointToCompressedStorage();

    Collection oidSet = getAllObjectIDs();
    // verify object IDs is in memory
    for (Iterator i = objects.iterator(); i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      assertTrue("Object:" + mo.getID() + " missed in memory! ", oidSet.contains(mo.getID()));
    }

    verify(objects);
  }

  public void testOidBitsArrayDeleteHalf() throws Exception {
    // wait for background retrieving persistent data
    objectStore.getAllObjectIDs();

    // publish data
    Collection objects = createRandomObjects(15050, false);
    PersistenceTransaction ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.saveAllObjects(ptx, objects);
    } finally {
      ptx.commit();
    }

    runCheckpointToCompressedStorage();

    int total = objects.size();
    SortedSet<ObjectID> toDelete = new TreeSet<ObjectID>();
    int count = 0;
    for (Iterator i = objects.iterator(); (count < total / 2) && i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      toDelete.add(mo.getID());
      i.remove();
    }

    ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.deleteAllObjectsByID(ptx, toDelete);
    } finally {
      ptx.commit();
    }

    runCheckpointToCompressedStorage();


    getAllObjectIDs();
    verify(objects);
  }

  public void testOidBitsArrayDeleteAll() throws Exception {
    // wait for background retrieving persistent data
    objectStore.getAllObjectIDs();

    // publish data
    Collection objects = createRandomObjects(15050, false);
    PersistenceTransaction ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.saveAllObjects(ptx, objects);
    } finally {
      ptx.commit();
    }

    runCheckpointToCompressedStorage();

    TreeSet<ObjectID> objectIds = new TreeSet<ObjectID>();
    for (Iterator i = objects.iterator(); i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      objectIds.add(mo.getID());
    }
    ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.deleteAllObjectsByID(ptx, objectIds);
    } finally {
      ptx.commit();
    }

    runCheckpointToCompressedStorage();


    objects.clear();
    verify(objects);
  }

  public void testStateOidBitsArraySave() throws Exception {
    // wait for background retrieving persistent data
    objectStore.getAllObjectIDs();

    // publish data with persistentCollectionMap
    Collection objects = createRandomObjects(15050, true);
    PersistenceTransaction ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.saveAllObjects(ptx, objects);
    } finally {
      ptx.commit();
    }

    runCheckpointToCompressedStorage();


    Collection oidSet = getAllObjectIDs();
    // verify object IDs is in memory
    for (Iterator i = objects.iterator(); i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      assertTrue("Object:" + mo.getID() + " missed in memory! ", oidSet.contains(mo.getID()));
    }
    verify(objects);

    oidSet = getAllMapsObjectIDs();
    verifyState(oidSet, objects);
  }

  public void testStateOidBitsArrayDeleteHalf() throws Exception {
    // wait for background retrieving persistent data
    objectStore.getAllObjectIDs();

    // publish data with persistentCollectionMap
    Collection objects = createRandomObjects(15050, true);
    PersistenceTransaction ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.saveAllObjects(ptx, objects);
    } finally {
      ptx.commit();
    }

    runCheckpointToCompressedStorage();

    int total = objects.size();
    SortedSet<ObjectID> toDelete = new TreeSet<ObjectID>();
    int count = 0;
    for (Iterator i = objects.iterator(); (count < total / 2) && i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      toDelete.add(mo.getID());
      i.remove();
    }
    testSleepycatCollectionsPersistor.setCounter(0);
    ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.deleteAllObjectsByID(ptx, toDelete);
    } finally {
      ptx.commit();
    }
    assertEquals(toDelete.size(), testSleepycatCollectionsPersistor.getCounter());

    runCheckpointToCompressedStorage();

    getAllObjectIDs();
    verify(objects);

    Collection oidSet = getAllMapsObjectIDs();
    verifyState(oidSet, objects);
  }

  public void testStateOidBitsArrayDeleteAll() throws Exception {
    // wait for background retrieving persistent data
    objectStore.getAllObjectIDs();

    // publish data
    Collection objects = createRandomObjects(15050, true);
    PersistenceTransaction ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.saveAllObjects(ptx, objects);
    } finally {
      ptx.commit();
    }

    runCheckpointToCompressedStorage();

    TreeSet<ObjectID> objectIds = new TreeSet<ObjectID>();
    for (Iterator i = objects.iterator(); i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      objectIds.add(mo.getID());
    }
    testSleepycatCollectionsPersistor.setCounter(0);
    ptx = persistenceTransactionProvider.newTransaction();
    try {
      managedObjectPersistor.deleteAllObjectsByID(ptx, objectIds);
    } finally {
      ptx.commit();
    }
    assertEquals(objectIds.size(), testSleepycatCollectionsPersistor.getCounter());

    runCheckpointToCompressedStorage();

    objects.clear();
    verify(objects);
    
    Collection oidSet = getAllMapsObjectIDs();
    verifyState(oidSet, objects);
  }

  private void runCheckpointToCompressedStorage() {
    oidManager.flushToCompressedStorage(new StoppedFlag(), Integer.MAX_VALUE);
  }

  private class TestSleepycatCollectionsPersistor extends SleepycatCollectionsPersistor {
    private int counter;

    public TestSleepycatCollectionsPersistor(TCLogger logger, Database mapsDatabase,
                                             SleepycatCollectionFactory sleepycatCollectionFactory) {
      super(logger, mapsDatabase, sleepycatCollectionFactory);
    }

    @Override
    public boolean deleteCollection(PersistenceTransaction tx, ObjectID id) {
      ++counter;
      return true;
    }

    public void setCounter(int value) {
      counter = value;
    }

    public int getCounter() {
      return counter;
    }
  }

  private class TestPersistentStateManagedObject extends TestManagedObject {

    private final ManagedObjectState state;

    public TestPersistentStateManagedObject(ObjectID id, ArrayList<ObjectID> references,
                                            boolean isPersistentCollectionMap) {
      super(id, references);
      byte type = (isPersistentCollectionMap) ? ManagedObjectState.MAP_TYPE : ManagedObjectState.PHYSICAL_TYPE;
      state = new TestManagedObjectState(type);
    }

    @Override
    public boolean isNew() {
      return true;
    }

    @Override
    public ManagedObjectState getManagedObjectState() {
      return state;
    }
  }

  private class TestManagedObjectState extends AbstractManagedObjectState {
    private final byte type;

    public TestManagedObjectState(byte type) {
      this.type = type;
    }

    @Override
    protected boolean basicEquals(AbstractManagedObjectState o) {
      return false;
    }

    public void addObjectReferencesTo(ManagedObjectTraverser traverser) {
      return;
    }

    public void apply(ObjectID objectID, DNACursor cursor, BackReferences includeIDs) {
      return;
    }

    public ManagedObjectFacade createFacade(ObjectID objectID, String className, int limit) {
      return null;
    }

    public void dehydrate(ObjectID objectID, DNAWriter writer) {
      return;
    }

    public String getClassName() {
      return null;
    }

    public String getLoaderDescription() {
      return null;
    }

    public Set getObjectReferences() {
      return null;
    }

    public byte getType() {
      return type;
    }

    public void writeTo(ObjectOutput o) {
      return;
    }

  }

}
