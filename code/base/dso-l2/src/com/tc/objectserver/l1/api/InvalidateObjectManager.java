/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.l1.api;

import com.tc.net.ClientID;
import com.tc.object.ObjectID;

import java.util.Set;

public interface InvalidateObjectManager {

  public void invalidateObjectFor(ClientID clientID, Set<ObjectID> oids);

  public Set<ObjectID> getObjectsIDsToInvalidate(ClientID clientID);

}
