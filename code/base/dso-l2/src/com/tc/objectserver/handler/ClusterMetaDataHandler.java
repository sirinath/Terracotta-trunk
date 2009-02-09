/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.objectserver.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventContext;
import com.tc.object.msg.NodesWithObjectsMessage;
import com.tc.objectserver.clustermetadata.ServerClusterMetaDataManager;
import com.tc.objectserver.core.api.ServerConfigurationContext;

public class ClusterMetaDataHandler extends AbstractEventHandler {

  private ServerClusterMetaDataManager clusterMetaDataManager;

  @Override
  public void handleEvent(final EventContext context) {
    final NodesWithObjectsMessage metaDataMsg = ((NodesWithObjectsMessage) context);
    this.clusterMetaDataManager.handleMessage(metaDataMsg);
  }

  @Override
  public void initialize(final ConfigurationContext ctxt) {
    super.initialize(ctxt);
    ServerConfigurationContext scc = ((ServerConfigurationContext) ctxt);
    this.clusterMetaDataManager = scc.getClusterMetaDataManager();
  }

}
