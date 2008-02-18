/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.groups;

import com.tc.async.api.EventContext;

public interface TCGroupMemberDiscovery extends GroupEventsListener {

  public void start() throws GroupException;

  public void stop();

  public void setupNodes(Node local, Node[] nodes);
  
  public Node getLocalNode();
  
  public void discoveryHandler(EventContext context);
  
  public void nodeZapped(NodeID nodeID);

}