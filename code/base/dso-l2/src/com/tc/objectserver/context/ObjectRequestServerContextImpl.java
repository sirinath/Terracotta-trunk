/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.context;

import com.tc.net.ClientID;
import com.tc.object.ObjectID;
import com.tc.object.ObjectRequestID;
import com.tc.object.ObjectRequestServerContext;

import java.util.SortedSet;

public class ObjectRequestServerContextImpl implements ObjectRequestServerContext {

  private final ClientID            requestedNodeID;
  private final ObjectRequestID     objectRequestID;
  private final SortedSet<ObjectID> lookupIDs;
  private final String              requestingThreadName;
  private final int                 requestDepth;
  private final boolean             serverInitiated;
  private final boolean             isPrefetched;

  public ObjectRequestServerContextImpl(ClientID requestNodeID, ObjectRequestID objectRequestID,
                                        SortedSet<ObjectID> lookupObjectIDs, String requestingThreadName,
                                        int requestDepth, boolean serverInitiated, boolean isPrefetched) {
    this.requestDepth = requestDepth;
    this.requestedNodeID = requestNodeID;
    this.objectRequestID = objectRequestID;
    this.lookupIDs = lookupObjectIDs;
    this.requestingThreadName = requestingThreadName;
    this.serverInitiated = serverInitiated;
    this.isPrefetched = isPrefetched;
  }

  /**
   * This is mutated outside, don't give a copy
   */
  public SortedSet<ObjectID> getRequestedObjectIDs() {
    return this.lookupIDs;
  }

  public int getRequestDepth() {
    return this.requestDepth;
  }

  public ObjectRequestID getRequestID() {
    return this.objectRequestID;
  }

  public ClientID getClientID() {
    return this.requestedNodeID;
  }

  public String getRequestingThreadName() {
    return this.requestingThreadName;
  }

  public boolean isServerInitiated() {
    return this.serverInitiated;
  }

  public boolean isPrefetched() {
    return this.isPrefetched;
  }

  public Object getKey() {
    return this.requestedNodeID;
  }
}
