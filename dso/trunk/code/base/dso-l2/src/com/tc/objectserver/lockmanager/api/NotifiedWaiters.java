/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.lockmanager.api;

import com.tc.net.groups.NodeID;
import com.tc.object.lockmanager.api.LockContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class NotifiedWaiters {

  private final Map notifiedSets = new HashMap();

  public String toString() {
    synchronized (notifiedSets) {
      return "NotifiedWaiters[" + notifiedSets + "]";
    }
  }

  public boolean isEmpty() {
    return notifiedSets.isEmpty();
  }

  public void addNotification(LockContext context) {
    synchronized (notifiedSets) {
      getOrCreateSetFor(context.getNodeID()).add(context);
    }
  }

  public Set getNotifiedFor(NodeID nodeID) {
    synchronized (notifiedSets) {
      Set rv = getSetFor(nodeID);
      return (rv == null) ? Collections.EMPTY_SET : rv;
    }
  }

  private Set getSetFor(NodeID nodeID) {
    return (Set) notifiedSets.get(nodeID);
  }

  private Set getOrCreateSetFor(NodeID nodeID) {
    Set rv = getSetFor(nodeID);
    if (rv == null) {
      rv = new HashSet();
      notifiedSets.put(nodeID, rv);
    }
    return rv;
  }

}
