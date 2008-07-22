/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.core.api;

import com.tc.objectserver.core.impl.GarbageCollectionInfo;

public interface GarbageCollectorEventListener {

  public void garbageCollectorStart(GarbageCollectionInfo info);

  public void garbageCollectorMark(GarbageCollectionInfo info);

  public void garbageCollectorMarkResults(GarbageCollectionInfo info);

  public void garbageCollectorRescue1Complete(GarbageCollectionInfo info);

  public void garbageCollectorPausing(GarbageCollectionInfo info);

  public void garbageCollectorPaused(GarbageCollectionInfo info);

  public void garbageCollectorRescue2Start(GarbageCollectionInfo info);

  public void garbageCollectorMarkComplete(GarbageCollectionInfo info);

  public void garbageCollectorDelete(GarbageCollectionInfo info);

  public void garbageCollectorCycleCompleted(GarbageCollectionInfo info);

  public void garbageCollectorCompleted(GarbageCollectionInfo info);

}
