/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.api;

import com.tc.l2.objectserver.ReplicatedObjectManager;
import com.tc.l2.objectserver.ReplicatedTransactionManager;
import com.tc.l2.state.StateManager;
import com.tc.net.groups.GroupManager;
import com.tc.net.groups.Node;

public interface L2Coordinator {

  public void start(Node thisNode, Node[] allNodes);

  public ReplicatedClusterStateManager getReplicatedClusterStateManager();

  public ReplicatedObjectManager getReplicatedObjectManager();

  public ReplicatedTransactionManager getReplicatedTransactionManager();

  public StateManager getStateManager();

  public GroupManager getGroupManager();

}
