/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventContext;
import com.tc.objectserver.api.ObjectRequestManager;
import com.tc.objectserver.context.RespondToObjectRequestContext;
import com.tc.objectserver.core.api.ServerConfigurationContext;

public class RespondToObjectRequestHandler extends AbstractEventHandler {

  private ObjectRequestManager objectRequestManager;

  @Override
  public void handleEvent(EventContext context) {
    RespondToObjectRequestContext rtorc = (RespondToObjectRequestContext) context;
    this.objectRequestManager.sendObjects(rtorc.getRequestedNodeID(), rtorc.getObjs(), rtorc.getRequestedObjectIDs(),
                                          rtorc.getMissingObjectIDs(), rtorc.isServerInitiated(), rtorc.isPreFetched(),
                                          rtorc.getRequestDepth());

  }

  @Override
  public void initialize(ConfigurationContext context) {
    super.initialize(context);
    ServerConfigurationContext oscc = (ServerConfigurationContext) context;
    this.objectRequestManager = oscc.getObjectRequestManager();
  }
}
