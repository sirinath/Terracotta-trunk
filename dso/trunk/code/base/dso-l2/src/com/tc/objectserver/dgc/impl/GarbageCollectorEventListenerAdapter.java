/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.objectserver.dgc.impl;

import com.tc.objectserver.dgc.api.GarbageCollectionInfo;
import com.tc.objectserver.dgc.api.GarbageCollectorEventListener;

public abstract class GarbageCollectorEventListenerAdapter implements GarbageCollectorEventListener {

  public void garbageCollectorCompleted(GarbageCollectionInfo info) {
    //do nothing
  }

  public void garbageCollectorCycleCompleted(GarbageCollectionInfo info) {
   //
  }

  public void garbageCollectorDelete(GarbageCollectionInfo info) {
   //
  }

  public void garbageCollectorMarkComplete(GarbageCollectionInfo info) {
    //
  }

  public void garbageCollectorMark(GarbageCollectionInfo info) {
    //
  }

  public void garbageCollectorMarkResults(GarbageCollectionInfo info) {
   //
  }

  public void garbageCollectorPaused(GarbageCollectionInfo info) {
    //
  }

  public void garbageCollectorPausing(GarbageCollectionInfo info) {
    //
  }

  public void garbageCollectorRescue1Complete(GarbageCollectionInfo info) {
    //
  }

  public void garbageCollectorRescue2Start(GarbageCollectionInfo info) {
    //
  }

  public void garbageCollectorStart(GarbageCollectionInfo info) {
    //
  }

}
