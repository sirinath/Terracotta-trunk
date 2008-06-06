/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.core.api;

import com.tc.object.ObjectID;
import com.tc.objectserver.api.GCStats;
import com.tc.objectserver.api.ObjectManagerEventListener;
import com.tc.objectserver.context.GCResultContext;
import com.tc.text.PrettyPrintable;
import com.tc.util.ObjectIDSet;
import com.tc.util.concurrent.LifeCycleState;
import com.tc.util.concurrent.StoppableThread;

import java.util.Collection;
import java.util.Set;

public interface GarbageCollector extends PrettyPrintable {

  public void enableGC();

  public boolean disableGC();

  public boolean isDisabled();

  public boolean isPausingOrPaused();

  public boolean isPaused();

  /**
   * Called by object manager. Notifies the garbage collector that it's ok to perform GC.
   */
  public void notifyReadyToGC();

  /**
   * Request to pause when the system state stabilizes
   */
  public void requestGCPause();

  /**
   * Called by the GC thread. Notifies the garbage collector that GC is complete.
   */
  public void notifyGCComplete();

  /**
   * @param traverser Determines whether or not to traverse a given tree node.
   * @param roots
   * @param managedObjects
   * @return An set on the objects that can be deleted
   */
  public ObjectIDSet collect(Filter traverser, Collection roots, ObjectIDSet managedObjectIds);

  public ObjectIDSet collect(Filter traverser, Collection roots, ObjectIDSet managedObjectIds, LifeCycleState state);

  public void changed(ObjectID changedObject, ObjectID oldReference, ObjectID newReference);

  public void gc();

  public void addNewReferencesTo(Set rescueIds);

  public void start();

  public void stop();

  public boolean isStarted();

  public void setState(StoppableThread st);

  public void addListener(ObjectManagerEventListener listener);

  public GCStats[] getGarbageCollectorStats();

  public boolean deleteGarbage(GCResultContext resultContext);
}