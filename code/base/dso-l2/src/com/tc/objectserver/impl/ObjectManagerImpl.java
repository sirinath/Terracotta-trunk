/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.impl;

import com.tc.async.api.Sink;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.protocol.tcm.ChannelID;
import com.tc.object.ObjectID;
import com.tc.object.cache.CacheStats;
import com.tc.object.cache.Evictable;
import com.tc.object.cache.EvictionPolicy;
import com.tc.objectserver.api.GCStats;
import com.tc.objectserver.api.NoSuchObjectException;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.api.ObjectManagerEventListener;
import com.tc.objectserver.api.ObjectManagerLookupResults;
import com.tc.objectserver.api.ObjectManagerMBean;
import com.tc.objectserver.api.ObjectManagerStatsListener;
import com.tc.objectserver.api.ShutdownError;
import com.tc.objectserver.context.ManagedObjectFaultingContext;
import com.tc.objectserver.context.ManagedObjectFlushingContext;
import com.tc.objectserver.context.ObjectManagerResultsContext;
import com.tc.objectserver.core.api.GarbageCollector;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.impl.NullGarbageCollector;
import com.tc.objectserver.l1.api.ClientStateManager;
import com.tc.objectserver.managedobject.ManagedObjectChangeListener;
import com.tc.objectserver.managedobject.ManagedObjectImpl;
import com.tc.objectserver.managedobject.ManagedObjectTraverser;
import com.tc.objectserver.mgmt.ManagedObjectFacade;
import com.tc.objectserver.persistence.api.ManagedObjectStore;
import com.tc.objectserver.persistence.api.PersistenceTransaction;
import com.tc.objectserver.persistence.api.PersistenceTransactionProvider;
import com.tc.objectserver.tx.NullTransactionalObjectManager;
import com.tc.objectserver.tx.TransactionalObjectManager;
import com.tc.objectserver.tx.TransactionalObjectManagerImpl;
import com.tc.properties.TCPropertiesImpl;
import com.tc.text.PrettyPrinter;
import com.tc.util.Assert;
import com.tc.util.Counter;
import com.tc.util.ObjectIDSet2;
import com.tc.util.concurrent.StoppableThread;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages access to all the Managed objects in the system.
 */
public class ObjectManagerImpl implements ObjectManager, ManagedObjectChangeListener, ObjectManagerMBean, Evictable {

  private static final TCLogger                logger                   = TCLogging.getLogger(ObjectManager.class);

  private static final int                     MAX_COMMIT_SIZE          = TCPropertiesImpl
                                                                            .getProperties()
                                                                            .getInt(
                                                                                    "l2.objectmanager.maxObjectsToCommit");
  // XXX:: Should go to property file
  private static final int                     INITIAL_SET_SIZE         = 16;
  private static final float                   LOAD_FACTOR              = 0.75f;
  private static final int                     MAX_LOOKUP_OBJECTS_COUNT = 5000;
  private static final long                    REMOVE_THRESHOLD         = 300;

  private final ManagedObjectStore             objectStore;
  private final Map                            references;
  private final EvictionPolicy                 evictionPolicy;
  private final Counter                        flushCount               = new Counter();
  private final PendingList                    pending                  = new PendingList();

  private GarbageCollector                     collector                = new NullGarbageCollector();
  private int                                  checkedOutCount          = 0;

  private volatile boolean                     inShutdown               = false;

  private ClientStateManager                   stateManager;
  private final ObjectManagerConfig            config;
  private final ThreadGroup                    gcThreadGroup;
  private ObjectManagerStatsListener           stats                    = new NullObjectManagerStatsListener();
  private final PersistenceTransactionProvider persistenceTransactionProvider;
  private final Sink                           faultSink;
  private final Sink                           flushSink;
  private TransactionalObjectManager           txnObjectMgr             = new NullTransactionalObjectManager();

  public ObjectManagerImpl(ObjectManagerConfig config, ThreadGroup gcThreadGroup, ClientStateManager stateManager,
                           ManagedObjectStore objectStore, EvictionPolicy cache,
                           PersistenceTransactionProvider persistenceTransactionProvider, Sink faultSink, Sink flushSink) {
    this.faultSink = faultSink;
    this.flushSink = flushSink;
    Assert.assertNotNull(objectStore);
    this.config = config;
    this.gcThreadGroup = gcThreadGroup;
    this.stateManager = stateManager;
    this.objectStore = objectStore;
    this.evictionPolicy = cache;
    this.persistenceTransactionProvider = persistenceTransactionProvider;
    this.references = new HashMap(10000);
  }

  public void setTransactionalObjectManager(TransactionalObjectManagerImpl txnObjectManager) {
    this.txnObjectMgr = txnObjectManager;
  }

  public void setStatsListener(ObjectManagerStatsListener statsListener) {
    this.stats = statsListener;
  }

  public void start() {
    collector.start();
  }

  public synchronized void stop() {
    this.inShutdown = true;

    collector.stop();

    // flush the cache to stable persistence.
    Set toFlush = new HashSet();
    for (Iterator i = references.values().iterator(); i.hasNext();) {
      ManagedObject obj = ((ManagedObjectReference) i.next()).getObject();
      if (!obj.isNew()) toFlush.add(obj);
    }
    PersistenceTransaction tx = newTransaction();
    flushAll(tx, toFlush);
    tx.commit();
  }

  public synchronized PrettyPrinter prettyPrint(PrettyPrinter out) {
    out.println(getClass().getName());
    out.indent().print("roots: ").println(getRoots());
    out.indent().print("collector: ").visit(collector).println();
    out.indent().print("references: ").visit(references).println();

    out.indent().println("checkedOutCount: " + checkedOutCount);
    out.indent().print("pending: ").visit(pending).println();

    out.indent().print("objectStore: ").duplicateAndIndent().visit(objectStore).println();
    out.indent().print("stateManager: ").duplicateAndIndent().visit(stateManager).println();
    return out;
  }

  public void addListener(ObjectManagerEventListener listener) {
    if (listener == null) { throw new NullPointerException("cannot add a null event listener"); }
    collector.addListener(listener);
  }

  public GCStats[] getGarbageCollectorStats() {
    return collector.getGarbageCollectorStats();
  }

  public ObjectID lookupRootID(String name) {
    syncAssertNotInShutdown();
    return objectStore.getRootID(name);
  }

  public boolean lookupObjectsAndSubObjectsFor(ChannelID channelID, ObjectManagerResultsContext responseContext,
                                               int maxReachableObjects) {
    // maxReachableObjects is at least 1 so that addReachableObjectsIfNecessary does the right thing
    return lookupObjectsForOptionallyCreate(channelID, responseContext, maxReachableObjects <= 0 ? 1
        : maxReachableObjects);
  }

  public boolean lookupObjectsFor(ChannelID channelID, ObjectManagerResultsContext responseContext) {
    return lookupObjectsForOptionallyCreate(channelID, responseContext, -1);
  }

  private synchronized boolean lookupObjectsForOptionallyCreate(ChannelID channelID,
                                                                ObjectManagerResultsContext responseContext,
                                                                int maxReachableObjects) {
    syncAssertNotInShutdown();

    if (collector.isPausingOrPaused()) {
      makePending(channelID, new ObjectManagerLookupContext(responseContext, false), maxReachableObjects);
      return false;
    }
    return basicLookupObjectsFor(channelID, new ObjectManagerLookupContext(responseContext, false), maxReachableObjects);
  }

  public Iterator getRoots() {
    syncAssertNotInShutdown();
    return objectStore.getRoots().iterator();
  }

  public Iterator getRootNames() {
    syncAssertNotInShutdown();
    return objectStore.getRootNames().iterator();
  }

  /**
   * For management use only (see interface documentation)
   */
  public ManagedObjectFacade lookupFacade(ObjectID id, int limit) throws NoSuchObjectException {
    final ManagedObject object = lookup(id, true);
    if (object == null) { throw new NoSuchObjectException(id); }

    try {
      return object.createFacade(limit);
    } finally {
      releaseReadOnly(object);
    }
  }

  private ManagedObject lookup(ObjectID id, boolean missingOk) {
    syncAssertNotInShutdown();

    WaitForLookupContext waitContext = new WaitForLookupContext(id, missingOk);
    ObjectManagerLookupContext context = new ObjectManagerLookupContext(waitContext, true);
    basicLookupObjectsFor(ChannelID.NULL_ID, context, -1);

    ManagedObject mo = waitContext.getLookedUpObject();
    if (mo == null) {
      Assert.assertTrue(missingOk);
    }
    return mo;
  }

  public ManagedObject getObjectByID(ObjectID id) {
    return lookup(id, false);
  }

  private void markReferenced(ManagedObjectReference reference) {
    if (reference.isReferenced()) { throw new AssertionError("Attempt to mark an already referenced object: "
                                                             + reference); }
    reference.markReference();
    checkedOutCount++;
  }

  private void unmarkReferenced(ManagedObjectReference reference) {
    if (!reference.isReferenced()) { throw new AssertionError("Attempt to unmark an unreferenced object: " + reference); }
    reference.unmarkReference();
    checkedOutCount--;
  }

  /**
   * Retrieves materialized references.
   */
  private ManagedObjectReference getReference(ObjectID id) {
    return (ManagedObjectReference) references.get(id);
  }

  /**
   * Retrieves materialized references-- if not materialized, will initiate a request to materialize them from the
   * object store.
   */
  private ManagedObjectReference getOrLookupReference(ObjectManagerLookupContext context, ObjectID id) {
    ManagedObjectReference rv = getReference(id);

    if (rv == null) {
      // Request Faulting in a different stage and give back a "Referenced" proxy
      ManagedObjectFaultingContext mofc = new ManagedObjectFaultingContext(id, context.removeOnRelease());
      faultSink.add(mofc);

      // don't account for a cache "miss" unless this was a real request
      // originating from a client
      stats.cacheMiss();
      rv = addNewReference(new FaultingManagedObjectReference(id));
    } else if (rv instanceof FaultingManagedObjectReference) {
      // Check to see if the retrieve was complete and the Object is missing
      FaultingManagedObjectReference fmr = (FaultingManagedObjectReference) rv;
      if (!fmr.isFaultingInProgress()) {
        references.remove(id);
        logger.warn("Request for non-exisitent object : " + id + " context = " + context);
        context.missingObject(id);
        return null;
      }
      if (context.isNewRequest()) stats.cacheMiss();
    } else {
      if (context.isNewRequest()) stats.cacheHit();
      if (!context.removeOnRelease()) {
        if (rv.isRemoveOnRelease()) {
          // This Object is faulted in by GC or Management interface with removeOnRelease = true, but before they got a
          // chance to grab it, a regular request for object is received. Take corrective action.
          rv.setRemoveOnRelease(false);
          evictionPolicy.add(rv);
        } else {
          evictionPolicy.markReferenced(rv);
        }
      }
    }
    return rv;
  }

  public synchronized void addFaultedObject(ObjectID oid, ManagedObject mo, boolean removeOnRelease) {
    FaultingManagedObjectReference fmor;
    if (mo == null) {
      ManagedObjectReference mor = (ManagedObjectReference) references.get(oid);
      if (mor == null || !(mor instanceof FaultingManagedObjectReference) || !oid.equals(mor.getObjectID())) {
        // Format
        throw new AssertionError("ManagedObjectReference is not what was expected : " + mor + " oid : " + oid);
      }
      fmor = (FaultingManagedObjectReference) mor;
      fmor.faultingFailed();
    } else {
      Assert.assertEquals(oid, mo.getID());
      ManagedObjectReference mor = (ManagedObjectReference) references.remove(oid);
      if (mor == null || !(mor instanceof FaultingManagedObjectReference) || !oid.equals(mor.getObjectID())) {
        // Format
        throw new AssertionError("ManagedObjectReference is not what was expected : " + mor + " oid : " + oid);
      }
      fmor = (FaultingManagedObjectReference) mor;
      addNewReference(mo, removeOnRelease);
    }
    makeUnBlocked(oid);
    postRelease();
  }

  private ManagedObjectReference addNewReference(ManagedObject obj, boolean isRemoveOnRelease) throws AssertionError {
    ManagedObjectReference newReference = obj.getReference();
    newReference.setRemoveOnRelease(isRemoveOnRelease);

    return addNewReference(newReference);
  }

  private ManagedObjectReference addNewReference(ManagedObjectReference newReference) {
    Assert.assertNull(references.put(newReference.getObjectID(), newReference));
    Assert.assertTrue(newReference.getNext() == null && newReference.getPrevious() == null);

    if (!newReference.isRemoveOnRelease()) {
      evictionPolicy.add(newReference);
    }
    return newReference;
  }

  private synchronized void reapCache(Collection removalCandidates, Collection toFlush, Collection removedObjects) {
    while (collector.isPausingOrPaused()) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        logger.error(e);
      }
    }
    for (Iterator i = removalCandidates.iterator(); i.hasNext();) {
      ManagedObjectReference removalCandidate = (ManagedObjectReference) i.next();
      if (removalCandidate != null && !removalCandidate.isReferenced() && !removalCandidate.isNew()) {
        evictionPolicy.remove(removalCandidate);
        if (removalCandidate.getObject().isDirty()) {
          markReferenced(removalCandidate);
          toFlush.add(removalCandidate.getObject());
        } else {
          // paranoid mode or the object is not dirty - just remove from reference
          removedObjects.add(references.remove(removalCandidate.getObjectID()));
        }
      }
    }
  }

  private void evicted(Collection managedObjects) {
    synchronized (this) {
      checkedOutCount -= managedObjects.size();
      for (Iterator i = managedObjects.iterator(); i.hasNext();) {
        ManagedObject mo = (ManagedObject) i.next();
        ObjectID oid = mo.getID();
        Object o = references.remove(oid);
        if (o == null) {
          logger.warn("Object ID : " + mo.getID()
                      + " was mapped to null but should have been mapped to a reference of  " + mo);
        } else {
          ManagedObjectReference ref = (ManagedObjectReference) o;
          if (isBlocked(oid)) {
            ref.unmarkReference();
            addNewReference(mo, ref.isRemoveOnRelease());
            makeUnBlocked(oid);
            i.remove();
          }
        }
      }
      postRelease();
    }

  }

  private synchronized boolean basicLookupObjectsFor(ChannelID channelID, ObjectManagerLookupContext context,
                                                     int maxReachableObjects) {
    Set objects = createNewSet();

    final Set newObjectIDs = context.getNewObjectIDs();
    boolean available = true;
    Set ids = context.getLookupIDs();
    for (Iterator i = ids.iterator(); i.hasNext();) {
      ObjectID id = (ObjectID) i.next();
      if (newObjectIDs.contains(id)) continue;
      // We don't check available flag before doing calling getOrLookupReference() for two reasons.
      // 1) To get the right hit/miss count and
      // 2) to Fault objects that are not available
      ManagedObjectReference reference = getOrLookupReference(context, id);
      if (reference == null) {
        continue;
      } else if (available && reference.isReferenced()) {
        available = false;
        // Setting only the first referenced object to process Pending. If objects are being faulted in, then this
        // will ensure that we don't run processPending multiple times unnecessarily.
        addBlocked(channelID, context, maxReachableObjects, id);
      }

      objects.add(reference);
    }

    if (available) {
      createNewObjectsAndAddTo(objects, newObjectIDs);
      Set processLater = addReachableObjectsIfNecessary(channelID, maxReachableObjects, objects);
      ObjectManagerLookupResults results = new ObjectManagerLookupResultsImpl(processObjectsRequest(objects),
                                                                              processLater);
      context.setResults(results);
    } else {
      context.makeOldRequest();
    }
    return available;
  }

  private void createNewObjectsAndAddTo(Set objects, Set newObjectIDs) {
    for (Iterator i = newObjectIDs.iterator(); i.hasNext();) {
      ObjectID oid = (ObjectID) i.next();
      ManagedObject mo = new ManagedObjectImpl(oid);
      createObject(mo);
      objects.add(mo.getReference());
    }
  }

  private Set addReachableObjectsIfNecessary(ChannelID channelID, int maxReachableObjects, Set objects) {
    if (maxReachableObjects <= 0) { return Collections.EMPTY_SET; }
    ManagedObjectTraverser traverser = new ManagedObjectTraverser(maxReachableObjects);
    Set lookedUpObjects = objects;
    do {
      traverser.traverse(lookedUpObjects);
      lookedUpObjects = new HashSet();
      Set lookupObjectIDs = traverser.getObjectsToLookup();
      stateManager.removeReferencedFrom(channelID, lookupObjectIDs);
      for (Iterator j = lookupObjectIDs.iterator(); j.hasNext();) {
        ObjectID id = (ObjectID) j.next();
        ManagedObjectReference newRef = getReference(id);
        // Note : Objects are looked up only if it is in the memory and not referenced
        if (newRef != null && !newRef.isReferenced()) {
          if (objects.add(newRef)) {
            lookedUpObjects.add(newRef);
          }
        }
      }
    } while (lookedUpObjects.size() > 0 && objects.size() < MAX_LOOKUP_OBJECTS_COUNT);
    return traverser.getPendingObjectsToLookup(lookedUpObjects);
  }

  // TODO:: Multiple readonly checkouts, now that there are more than 1 thread faulting objects to the
  // client
  public void releaseReadOnly(ManagedObject object) {
    synchronized (this) {
      basicRelease(object);
      postRelease();
    }

  }

  public void release(PersistenceTransaction persistenceTransaction, ManagedObject object) {
    if (config.paranoid()) flush(persistenceTransaction, object);
    synchronized (this) {
      basicRelease(object);
      postRelease();
    }

  }

  public synchronized void releaseAll(Collection objects) {
    for (Iterator i = objects.iterator(); i.hasNext();) {
      ManagedObject mo = (ManagedObject) i.next();
      if (config.paranoid()) {
        Assert.assertFalse(mo.isDirty());
      }
      basicRelease(mo);
    }
    postRelease();
  }

  public void releaseAll(PersistenceTransaction persistenceTransaction, Collection managedObjects) {
    if (config.paranoid()) flushAll(persistenceTransaction, managedObjects);
    synchronized (this) {
      for (Iterator i = managedObjects.iterator(); i.hasNext();) {
        basicRelease((ManagedObject) i.next());
      }
      postRelease();
    }
  }

  private void removeAllObjectsByID(Set toDelete) {
    for (Iterator i = toDelete.iterator(); i.hasNext();) {
      ObjectID id = (ObjectID) i.next();
      ManagedObjectReference ref = (ManagedObjectReference) references.remove(id);
      if (ref != null) {
        Assert.assertFalse(ref.isReferenced() || ref.isNew());
        evictionPolicy.remove(ref);
      }
    }
  }

  public synchronized int getCheckedOutCount() {
    return checkedOutCount;
  }

  public Set getRootIDs() {
    return objectStore.getRoots();
  }

  public Map getRootNamesToIDsMap() {
    return objectStore.getRootNamesToIDsMap();
  }

  public ObjectIDSet2 getAllObjectIDs() {
    return objectStore.getAllObjectIDs();
  }

  private void postRelease() {
    if (collector.isPausingOrPaused()) {
      checkAndNotifyGC();
    } else if (pending.size() > 0) {
      processPendingLookups();
    }
    notifyAll();
  }

  private void basicRelease(ManagedObject object) {
    ManagedObjectReference mor = object.getReference();
    removeReferenceIfNecessary(mor);
    unmarkReferenced(mor);
    makeUnBlocked(object.getID());
  }

  private void removeReferenceIfNecessary(ManagedObjectReference mor) {
    if (mor.isRemoveOnRelease()) {
      if (mor.getObject().isDirty()) {
        logger.error(mor + " is DIRTY");
        throw new AssertionError(mor + " is DIRTY");
      }
      Object removed = references.remove(mor.getObjectID());
      Assert.assertNotNull(removed);
    }
  }

  private void checkAndNotifyGC() {
    if (checkedOutCount == 0) {
      logger.info("Notifying GC : pending = " + pending.size() + " checkedOutCount = " + checkedOutCount);
      collector.notifyReadyToGC();
    }
  }

  public synchronized void waitUntilReadyToGC() {
    checkAndNotifyGC();
    txnObjectMgr.recallAllCheckedoutObject();
    while (!collector.isPaused()) {
      try {
        this.wait(10000);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  public void notifyGCComplete(Set toDelete) {
    synchronized (this) {
      collector.notifyGCDeleteStarted();
      removeAllObjectsByID(toDelete);
      // Process pending, since we disabled process pending while GC pause was initiate.
      processPendingLookups();
      notifyAll();
    }

    if (toDelete.size() <= config.getDeleteBatchSize()) {
      removeFromStore(toDelete);
    } else {
      Set split = new HashSet();
      for (Iterator i = toDelete.iterator(); i.hasNext();) {
        split.add(i.next());
        if (split.size() >= config.getDeleteBatchSize()) {
          removeFromStore(split);
          split = new HashSet();
        }
      }
      if (split.size() > 0) {
        removeFromStore(split);
      }
    }
    collector.notifyGCComplete();
  }

  private void removeFromStore(Set toDelete) {
    long start = System.currentTimeMillis();

    PersistenceTransaction tx = newTransaction();
    objectStore.removeAllObjectsByIDNow(tx, toDelete);
    tx.commit();

    long elapsed = System.currentTimeMillis() - start;
    if (elapsed > REMOVE_THRESHOLD) {
      logger.info("Removed " + toDelete.size() + " objects in " + elapsed + "ms.");
    }
  }

  private void flush(PersistenceTransaction persistenceTransaction, ManagedObject managedObject) {
    objectStore.commitObject(persistenceTransaction, managedObject);
  }

  private void flushAll(PersistenceTransaction persistenceTransaction, Collection managedObjects) {
    objectStore.commitAllObjects(persistenceTransaction, managedObjects);
  }

  public void dump() {
    PrintWriter pw = new PrintWriter(System.err);
    new PrettyPrinter(pw).visit(this);
    pw.flush();
  }

  // This method is for tests only
  public synchronized boolean isReferenced(ObjectID id) {
    ManagedObjectReference reference = getReference(id);
    return reference != null && reference.isReferenced();
  }

  // This method is public for testing purpose
  public synchronized void createObject(ManagedObject object) {
    syncAssertNotInShutdown();
    Assert.eval(object.getID().toLong() != -1);
    objectStore.addNewObject(object);
    addNewReference(object, false);
    stats.newObjectCreated();
  }

  public void createRoot(String rootName, ObjectID id) {
    syncAssertNotInShutdown();
    PersistenceTransaction tx = newTransaction();
    objectStore.addNewRoot(tx, rootName, id);
    tx.commit();
    stats.newObjectCreated();
    // This change needs to be notified so that new roots are not missedout
    changed(null, null, id);
  }

  private PersistenceTransaction newTransaction() {
    return this.persistenceTransactionProvider.newTransaction();
  }

  public GarbageCollector getGarbageCollector() {
    return this.collector;
  }

  public void setGarbageCollector(final GarbageCollector newCollector) {
    syncAssertNotInShutdown();
    if (this.collector != null) {
      this.collector.stop();
    }
    this.collector = newCollector;

    if (!config.doGC() || config.gcThreadSleepTime() < 0) return;

    final Object stopLock = new Object();

    StoppableThread st = new StoppableThread(this.gcThreadGroup, "GC") {
      public void requestStop() {
        super.requestStop();

        synchronized (stopLock) {
          stopLock.notifyAll();
        }
      }

      public void run() {
        final long gcSleepTime = config.gcThreadSleepTime();

        while (true) {
          try {
            if (isStopRequested()) { return; }
            synchronized (stopLock) {
              stopLock.wait(gcSleepTime);
            }
            if (isStopRequested()) { return; }
            newCollector.gc();
          } catch (InterruptedException ie) {
            throw new TCRuntimeException(ie);
          }
        }
      }

    };
    st.setDaemon(true);
    newCollector.setState(st);
  }

  private Map processObjectsRequest(Collection objects) {
    Map results = new HashMap();
    for (Iterator i = objects.iterator(); i.hasNext();) {
      ManagedObjectReference mor = (ManagedObjectReference) i.next();
      Assert.assertNotNull(mor);
      if (!mor.isReferenced()) {
        markReferenced(mor);
      }
      if (mor.getObject() == null) {
        logger.error("Object is NULL for " + mor);
        throw new AssertionError("ManagedObject is null.");
      }
      results.put(mor.getObjectID(), mor.getObject());
    }
    return results;
  }

  private void processPendingLookups() {
    List lp = pending.getAndResetPendingRequests();
    for (Iterator i = lp.iterator(); i.hasNext();) {
      Pending p = (Pending) i.next();
      basicLookupObjectsFor(p.getChannelID(), p.getRequestContext(), p.getMaxReachableObjects());
    }
  }

  private void addBlocked(ChannelID channelID, ObjectManagerLookupContext context, int maxReachableObjects,
                          ObjectID blockedOid) {
    pending.makeBlocked(blockedOid, new Pending(channelID, context, maxReachableObjects));

    if (context.getProcessedCount() % 500 == 499) {
      logger.warn("Reached " + context.getProcessedCount() + " Pending size : " + pending.size()
                  + " : basic look up for : " + context + " maxReachable depth : " + maxReachableObjects);
    }
  }

  private void makeUnBlocked(ObjectID id) {
    pending.makeUnBlocked(id);
  }

  private boolean isBlocked(ObjectID id) {
    return pending.isBlocked(id);
  }

  private void makePending(ChannelID channelID, ObjectManagerLookupContext context, int maxReachableObjects) {
    pending.addPending(new Pending(channelID, context, maxReachableObjects));
  }

  private void syncAssertNotInShutdown() {
    assertNotInShutdown();
  }

  private void assertNotInShutdown() {
    if (this.inShutdown) throw new ShutdownError();
  }

  public void evictCache(CacheStats stat) {
    int size = references_size();
    int toEvict = stat.getObjectCountToEvict(size);
    if (toEvict <= 0) return;

    // This could be a costly call, so call just once
    Collection removalCandidates = evictionPolicy.getRemovalCandidates(toEvict);

    HashSet toFlush = new HashSet();
    ArrayList removed = new ArrayList();
    reapCache(removalCandidates, toFlush, removed);

    int evicted = (toFlush.size() + removed.size());
    // Let GC work for us
    removed = null;
    removalCandidates = null;

    if (!toFlush.isEmpty()) {
      initateFlushRequest(toFlush);
      toFlush = null; // make GC work
      waitUntilFlushComplete();
    }

    // TODO:: Send the right objects to the cache manager
    stat.objectEvicted(evicted, references_size(), Collections.EMPTY_LIST);
  }

  private void waitUntilFlushComplete() {
    flushCount.waitUntil(0);
  }

  private void initateFlushRequest(Collection toFlush) {
    flushCount.increment(toFlush.size());
    for (Iterator i = toFlush.iterator(); i.hasNext();) {
      int count = 0;
      ManagedObjectFlushingContext mofc = new ManagedObjectFlushingContext();
      while (count < MAX_COMMIT_SIZE && i.hasNext()) {
        mofc.addObjectToFlush(i.next());
        count++;
        // i.remove();
      }
      flushSink.add(mofc);
    }
  }

  public void flushAndEvict(List objects2Flush) {
    PersistenceTransaction tx = newTransaction();
    int size = objects2Flush.size();
    flushAll(tx, objects2Flush);
    tx.commit();
    evicted(objects2Flush);
    flushCount.decrement(size);
  }

  // XXX:: This is not synchronized and might not give us the right number. Performance over accuracy. This is to be
  // used only in evictCache method.
  private int references_size() {
    return references.size();
  }

  private static class ObjectManagerLookupContext implements ObjectManagerResultsContext {

    private final ObjectManagerResultsContext responseContext;
    private final boolean                     removeOnRelease;
    private int                               processedCount = 0;

    public ObjectManagerLookupContext(ObjectManagerResultsContext responseContext, boolean removeOnRelease) {
      this.responseContext = responseContext;
      this.removeOnRelease = removeOnRelease;
    }

    public void makeOldRequest() {
      processedCount++;
    }

    public int getProcessedCount() {
      return processedCount;
    }

    public boolean isNewRequest() {
      return processedCount == 0;
    }

    public boolean removeOnRelease() {
      return removeOnRelease;
    }

    public Set getLookupIDs() {
      return responseContext.getLookupIDs();
    }

    public Set getNewObjectIDs() {
      return responseContext.getNewObjectIDs();
    }

    public void setResults(ObjectManagerLookupResults results) {
      responseContext.setResults(results);
    }

    public void missingObject(ObjectID oid) {
      responseContext.missingObject(oid);
    }

    public String toString() {
      return "ObjectManagerLookupContext : [ processed count = " + processedCount + ", responseContext = "
             + responseContext + "] ";
    }
  }

  private static class WaitForLookupContext implements ObjectManagerResultsContext {

    private final ObjectID lookupID;
    private final boolean  missingOk;
    private final Set      lookupIDs = new HashSet();
    private boolean        resultSet = false;
    private ManagedObject  result;

    public WaitForLookupContext(ObjectID id, boolean missingOk) {
      this.lookupID = id;
      this.missingOk = missingOk;
      lookupIDs.add(id);
    }

    public synchronized ManagedObject getLookedUpObject() {
      while (!resultSet) {
        try {
          wait();
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
      }
      return result;
    }

    public Set getLookupIDs() {
      return lookupIDs;
    }

    public Set getNewObjectIDs() {
      return Collections.EMPTY_SET;
    }

    public synchronized void setResults(ObjectManagerLookupResults results) {
      resultSet = true;
      Map objects = results.getObjects();
      Assert.assertTrue(objects.size() == 0 || objects.size() == 1);
      if (objects.size() == 1) {
        result = (ManagedObject) objects.get(lookupID);
        Assert.assertNotNull(result);
      }
      notifyAll();
    }

    public void missingObject(ObjectID oid) {
      if (!missingOk) { throw new AssertionError("Lookup of non-exisiting object : " + oid + " " + this); }
    }

    public String toString() {
      return "WaitForLookupContext [ " + lookupID + ", missingOK = " + missingOk + "]";
    }

  }

  private static class Pending {
    private final ObjectManagerLookupContext context;
    private final ChannelID                  groupingKey;
    private final int                        maxReachableObjects;

    public Pending(ChannelID groupingKey, ObjectManagerLookupContext context, int maxReachableObjects) {
      this.groupingKey = groupingKey;
      this.context = context;
      this.maxReachableObjects = maxReachableObjects;
    }

    public String toString() {
      return "ObjectManagerImpl.Pending[groupingKey=" + groupingKey + "]";

    }

    public ChannelID getChannelID() {
      return this.groupingKey;
    }

    public ObjectManagerLookupContext getRequestContext() {
      return context;
    }

    public int getMaxReachableObjects() {
      return maxReachableObjects;
    }

  }

  private static class PendingList {
    List pending = new ArrayList();
    Map  blocked = new HashMap();

    public void makeBlocked(ObjectID blockedOid, Pending pd) {
      ArrayList blockedRequests = (ArrayList) blocked.get(blockedOid);
      if (blockedRequests == null) {
        blockedRequests = new ArrayList(1);
        blocked.put(blockedOid, blockedRequests);
      }
      blockedRequests.add(pd);
    }

    public boolean isBlocked(ObjectID id) {
      return blocked.containsKey(id);
    }

    public void makeUnBlocked(ObjectID id) {
      ArrayList blockedRequests = (ArrayList) blocked.remove(id);
      if (blockedRequests != null) {
        pending.addAll(blockedRequests);
      }
    }

    public List getAndResetPendingRequests() {
      List rv = pending;
      pending = new ArrayList();
      return rv;
    }

    public void addPending(Pending pd) {
      pending.add(pd);
    }

    public int size() {
      return pending.size();
    }
  }

  /*********************************************************************************************************************
   * ManagedObjectChangeListener interface
   */
  public void changed(ObjectID changedObject, ObjectID oldReference, ObjectID newReference) {
    collector.changed(changedObject, oldReference, newReference);
  }

  private static Set createNewSet() {
    return new HashSet(INITIAL_SET_SIZE, LOAD_FACTOR);
  }
}
