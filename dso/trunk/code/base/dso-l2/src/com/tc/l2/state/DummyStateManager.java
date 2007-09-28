/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.state;

import com.tc.l2.context.StateChangedEvent;
import com.tc.l2.msg.L2StateMessage;
import com.tc.net.groups.NodeID;
import com.tc.net.groups.NodeIDImpl;

public class DummyStateManager implements StateManager {

  public void fireStateChangedEvent(StateChangedEvent sce) {
    // Nop
  }

  public boolean isActiveCoordinator() {
    return true;
  }

  public void moveNodeToPassiveStandby(NodeID nodeID) {
    throw new UnsupportedOperationException();
  }

  public void registerForStateChangeEvents(StateChangeListener listener) {
    // Noop
  }

  public void startElection() {
    // Nop
  }

  public void publishActiveState(NodeID nodeID) {
    // Nop
  }

  public void startElectionIfNecessary(NodeID disconnectedNode) {
    // Nop
  }

  public void moveToPassiveStandbyState() {
    throw new UnsupportedOperationException();
  }

  public void handleClusterStateMessage(L2StateMessage clusterMsg) {
    throw new UnsupportedOperationException();
  }

  public NodeID getActiveNodeID() {
    return NodeIDImpl.NULL_ID;
  }

}
