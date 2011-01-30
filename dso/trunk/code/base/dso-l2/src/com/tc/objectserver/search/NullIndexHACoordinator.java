/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.search;

import com.tc.l2.context.StateChangedEvent;
import com.tc.l2.state.StateManager;
import com.tc.net.NodeID;

import java.io.IOException;

public class NullIndexHACoordinator extends NullIndexManager implements IndexHACoordinator {

  public void setStateManager(StateManager stateManager) {
    //
  }

  @SuppressWarnings("unused")
  public void applyTempJournals() throws IOException {
    //
  }

  public void l2StateChanged(StateChangedEvent sce) {
    //
  }

  public void applyIndexSync(String cacheName, String fileName, byte[] data) {
    //  
  }

  public void nodeJoined(NodeID nodeID) {
    //
  }

  public void nodeLeft(NodeID nodeID) {
    //
  }

}
