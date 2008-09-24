/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object.msg;

import com.tc.lang.Recyclable;
import com.tc.net.NodeID;

public interface RequestRootMessage extends Recyclable {

  public String getRootName();

  public void initialize(String name);

  public void send();
  
  public NodeID getSourceNodeID();

}