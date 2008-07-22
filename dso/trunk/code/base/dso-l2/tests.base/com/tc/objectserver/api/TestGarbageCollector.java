/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.api;

import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;

import com.tc.exception.ImplementMe;
import com.tc.object.ObjectID;
import com.tc.objectserver.context.GCResultContext;
import com.tc.objectserver.core.api.Filter;
import com.tc.objectserver.core.api.GarbageCollector;
import com.tc.objectserver.core.api.GarbageCollectorEventListener;
import com.tc.text.PrettyPrinter;
import com.tc.util.Assert;
import com.tc.util.ObjectIDSet;
import com.tc.util.TCCollections;
import com.tc.util.concurrent.LifeCycleState;
import com.tc.util.concurrent.NullLifeCycleState;
import com.tc.util.concurrent.StoppableThread;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class TestGarbageCollector implements GarbageCollector {
  public ObjectIDSet    collectedObjects = new ObjectIDSet();
  private boolean       collected        = false;
  private boolean       isPausing        = false;
  private boolean       isPaused         = false;

  private LinkedQueue   collectCalls;
  private LinkedQueue   notifyReadyToGCCalls;
  private LinkedQueue   notifyGCCompleteCalls;
  private LinkedQueue   requestGCCalls;
  private LinkedQueue   blockUntilReadyToGCCalls;
  private LinkedQueue   blockUntilReadyToGCQueue;
  private ObjectManager objectProvider;

  public TestGarbageCollector(ObjectManager objectProvider) {
    initQueues();
    this.objectProvider = objectProvider;
  }

  private void initQueues() {
    collectCalls = new LinkedQueue();
    notifyReadyToGCCalls = new LinkedQueue();
    notifyGCCompleteCalls = new LinkedQueue();
    requestGCCalls = new LinkedQueue();
    blockUntilReadyToGCCalls = new LinkedQueue();
    blockUntilReadyToGCQueue = new LinkedQueue();
  }

  private List drainQueue(LinkedQueue queue) {
    List rv = new ArrayList();
    while (queue.peek() != null) {
      try {
        rv.add(queue.take());
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
    return rv;
  }

  public synchronized void reset() {
    collectedObjects.clear();
    collected = false;
    isPausing = false;
    initQueues();
  }

  public ObjectIDSet collect(Filter filter, Collection rootIds, ObjectIDSet managedObjectIds) {
    try {
      collectCalls.put(new CollectCallContext(filter, rootIds, managedObjectIds, objectProvider));
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    this.collected = true;
    return collectedObjects;
  }

  public boolean collectWasCalled() {
    return collectCalls.peek() != null;
  }

  public boolean waitForCollectToBeCalled(long timeout) {
    try {
      return collectCalls.poll(timeout) != null;
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public CollectCallContext getNextCollectCall() {
    try {
      return (CollectCallContext) collectCalls.take();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public List getCollectCalls() {
    return drainQueue(collectCalls);
  }

  public static class CollectCallContext {
    public final Filter                filter;
    public final Collection            roots;
    public final Set                   managedObjectIds;
    public final ManagedObjectProvider objectProvider;

    private CollectCallContext(Filter filter, Collection roots, Set managedObjectIds,
                               ManagedObjectProvider objectProvider) {
      this.filter = filter;
      this.roots = Collections.unmodifiableCollection(roots);
      this.managedObjectIds = Collections.unmodifiableSet(managedObjectIds);
      this.objectProvider = objectProvider;
    }
  }

  public boolean isCollected() {
    return this.collected;
  }

  public synchronized boolean isPausingOrPaused() {
    return isPausing || isPaused;
  }

  public synchronized boolean isPaused() {
    return isPaused;
  }

  public void notifyReadyToGC() {
    try {
      isPaused = true;
      notifyReadyToGCCalls.put(new Object());
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public boolean notifyReadyToGC_WasCalled() {
    return notifyReadyToGCCalls.peek() != null;
  }

  public boolean waitFor_notifyReadyToGC_ToBeCalled(long timeout) {
    try {
      return notifyReadyToGCCalls.poll(timeout) != null;
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void blockUntilReadyToGC() {
    try {
      blockUntilReadyToGCCalls.put(new Object());
      blockUntilReadyToGCQueue.take();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void allow_blockUntilReadyToGC_ToProceed() {
    try {
      Assert.eval("queue was not empty!", blockUntilReadyToGCQueue.peek() == null);
      blockUntilReadyToGCQueue.put(new Object());
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void waitUntil_blockUntilReadyToGC_IsCalled() {
    try {
      blockUntilReadyToGCCalls.take();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public boolean waitFor_blockUntilReadyToGC_ToBeCalled(int timeout) {
    try {
      return blockUntilReadyToGCCalls.poll(timeout) != null;
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public boolean blockUntilReadyToGC_WasCalled() {
    return blockUntilReadyToGCCalls.peek() != null;
  }

  public void notifyGCComplete() {
    try {
      isPausing = false;
      isPaused = false;
      notifyGCCompleteCalls.put(new Object());
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    return;
  }

  public void waitUntil_notifyGCComplete_IsCalled() {
    try {
      notifyGCCompleteCalls.take();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public boolean waitFor_notifyGCComplete_ToBeCalled(long timeout) {
    try {
      return notifyGCCompleteCalls.poll(timeout) != null;
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void requestGCPause() {
    try {
      isPausing = true;
      isPaused = false;
      requestGCCalls.put(new Object());
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    return;
  }

  public PrettyPrinter prettyPrint(PrettyPrinter out) {
    return out.print(getClass().getName());
  }

  public ObjectIDSet collect(Filter traverser, Collection roots, ObjectIDSet managedObjectIds, LifeCycleState state) {
    return collect(traverser, roots, managedObjectIds);
  }

  public void changed(ObjectID changedObject, ObjectID oldReference, ObjectID newReference) {
    throw new ImplementMe();

  }

  public void gc() {
    collect(null, objectProvider.getRootIDs(), objectProvider.getAllObjectIDs(), new NullLifeCycleState());
    this.requestGCPause();
    this.blockUntilReadyToGC();
    this.deleteGarbage(new GCResultContext(1,TCCollections.EMPTY_OBJECT_ID_SET));
  }

  public void addNewReferencesTo(Set rescueIds) {
    throw new ImplementMe();

  }

  public void start() {
    // Nop
  }

  public void stop() {
    throw new ImplementMe();

  }

  public void setState(StoppableThread st) {
    throw new ImplementMe();

  }

  public void addListener(GarbageCollectorEventListener listener) {
    //
  }

  public GCStats[] getGarbageCollectorStats() {
    return null;
  }

  public boolean disableGC() {
    return false;
  }

  public void enableGC() {
    throw new ImplementMe();

  }

  public boolean isDisabled() {
    return false;
  }

  public boolean isStarted() {
    return false;
  }

  public boolean deleteGarbage(GCResultContext resultContext) {
    this.objectProvider.notifyGCComplete(resultContext);
    this.notifyGCComplete();
    return true;
  }
  
  public void gcYoung() {
    throw new ImplementMe();
  }

  public void notifyNewObjectInitalized(ObjectID id) {
    // NOP
  }

  public void notifyObjectCreated(ObjectID id) {
    // NOP
  }

  public void notifyObjectsEvicted(Collection evicted) {
    // NOP
  }

}