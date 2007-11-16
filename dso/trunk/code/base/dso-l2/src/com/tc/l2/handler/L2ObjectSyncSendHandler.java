/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventContext;
import com.tc.async.api.Sink;
import com.tc.l2.api.L2Coordinator;
import com.tc.l2.context.ManagedObjectSyncContext;
import com.tc.l2.context.SyncObjectsRequest;
import com.tc.l2.ha.L2HAZapNodeRequestProcessor;
import com.tc.l2.msg.ObjectSyncMessage;
import com.tc.l2.msg.ObjectSyncMessageFactory;
import com.tc.l2.msg.ServerTxnAckMessage;
import com.tc.l2.objectserver.L2ObjectStateManager;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.groups.GroupException;
import com.tc.net.groups.GroupManager;
import com.tc.objectserver.core.api.ServerConfigurationContext;

public class L2ObjectSyncSendHandler extends AbstractEventHandler {

  private static final TCLogger      logger = TCLogging.getLogger(L2ObjectSyncSendHandler.class);

  private final L2ObjectStateManager objectStateManager;
  private GroupManager               groupManager;

  private Sink                       syncRequestSink;

  public L2ObjectSyncSendHandler(L2ObjectStateManager objectStateManager) {
    this.objectStateManager = objectStateManager;
  }

  public void handleEvent(EventContext context) {
    if (context instanceof ManagedObjectSyncContext) {
      ManagedObjectSyncContext mosc = (ManagedObjectSyncContext) context;
      if (sendObjects(mosc)) {
        if (mosc.hasMore()) {
          syncRequestSink.add(new SyncObjectsRequest(mosc.getNodeID()));
        }
      }
    } else if (context instanceof ServerTxnAckMessage) {
      ServerTxnAckMessage txnMsg = (ServerTxnAckMessage) context;
      sendAcks(txnMsg);
    } else {
      throw new AssertionError("Unknown context type : " + context.getClass().getName() + " : " + context);
    }
  }

  private void sendAcks(ServerTxnAckMessage ackMsg) {
    try {
      this.groupManager.sendTo(ackMsg.getDestinationID(), ackMsg);
    } catch (GroupException e) {
      String error = "ERROR sending ACKS: Caught exception while sending message to ACTIVE";
      logger.error(error, e);
      // try Zapping the active server so that a split brain war is initiated, at least we won't hold the whole cluster
      // down.
      groupManager.zapNode(ackMsg.getDestinationID(), L2HAZapNodeRequestProcessor.COMMUNICATION_TO_ACTIVE_ERROR,
                           error + L2HAZapNodeRequestProcessor.getErrorString(e));
    }
  }

  private boolean sendObjects(ManagedObjectSyncContext mosc) {
    ObjectSyncMessage msg = ObjectSyncMessageFactory.createObjectSyncMessageFrom(mosc);
    try {
      this.groupManager.sendTo(mosc.getNodeID(), msg);
      logger.info("Sent " + mosc.getDNACount() + " objects to " + mosc.getNodeID() + " roots = "
                  + mosc.getRootsMap().size());
      objectStateManager.close(mosc);
      return true;
    } catch (GroupException e) {
      logger.error("Removing " + mosc.getNodeID() + " from group because of Exception :", e);
      groupManager.zapNode(mosc.getNodeID(), L2HAZapNodeRequestProcessor.COMMUNICATION_ERROR,
                           "Error sending objects." + L2HAZapNodeRequestProcessor.getErrorString(e));
      return false;
    }
  }

  public void initialize(ConfigurationContext context) {
    super.initialize(context);
    ServerConfigurationContext oscc = (ServerConfigurationContext) context;
    L2Coordinator l2Coordinator = oscc.getL2Coordinator();
    this.groupManager = l2Coordinator.getGroupManager();
    this.syncRequestSink = oscc.getStage(ServerConfigurationContext.OBJECTS_SYNC_REQUEST_STAGE).getSink();
  }

}
