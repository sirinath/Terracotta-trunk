/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventContext;
import com.tc.async.api.Sink;
import com.tc.l2.msg.ObjectSyncCompleteMessage;
import com.tc.l2.msg.ObjectSyncMessage;
import com.tc.l2.msg.RelayedCommitTransactionMessage;
import com.tc.l2.msg.ServerTxnAckMessage;
import com.tc.l2.msg.ServerTxnAckMessageFactory;
import com.tc.l2.objectserver.ReplicatedTransactionManager;
import com.tc.l2.objectserver.ServerTransactionFactory;
import com.tc.l2.state.StateManager;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.gtx.GlobalTransactionID;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.tx.ServerTransaction;
import com.tc.objectserver.tx.TransactionBatchReader;
import com.tc.objectserver.tx.TransactionBatchReaderFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class L2ObjectSyncHandler extends AbstractEventHandler {

  private static final TCLogger         logger = TCLogging.getLogger(L2ObjectSyncHandler.class);

  private TransactionBatchReaderFactory batchReaderFactory;

  private Sink                          sendSink;
  private ReplicatedTransactionManager  rTxnManager;
  private StateManager                  stateManager;

  public void handleEvent(EventContext context) {
    if (context instanceof ObjectSyncMessage) {
      ObjectSyncMessage syncMsg = (ObjectSyncMessage) context;
      doSyncObjectsResponse(syncMsg);
    } else if (context instanceof RelayedCommitTransactionMessage) {
      RelayedCommitTransactionMessage commitMessage = (RelayedCommitTransactionMessage) context;
      Set serverTxnIDs = processCommitTransactionMessage(commitMessage);
      processTransactionLowWaterMark(commitMessage.getLowGlobalTransactionIDWatermark());
      ackTransactions(commitMessage, serverTxnIDs);
    } else if (context instanceof ObjectSyncCompleteMessage) {
      ObjectSyncCompleteMessage msg = (ObjectSyncCompleteMessage) context;
      logger.info("Received ObjectSyncComplete Msg from : " + msg.messageFrom() + " msg : " + msg);
      // Now this node can move to Passive StandBy
      stateManager.moveToPassiveStandbyState();
    } else {
      throw new AssertionError("Unknown context type : " + context.getClass().getName() + " : " + context);
    }
  }

  private void processTransactionLowWaterMark(GlobalTransactionID lowGlobalTransactionIDWatermark) {
    // TODO:: This processing could be handled by another stage thread.
    rTxnManager.clearTransactionsBelowLowWaterMark(lowGlobalTransactionIDWatermark);
  }

  // TODO:: Implement throttling between active/passive
  private void ackTransactions(RelayedCommitTransactionMessage commitMessage, Set serverTxnIDs) {
    ServerTxnAckMessage msg = ServerTxnAckMessageFactory.createServerTxnAckMessage(commitMessage, serverTxnIDs);
    sendSink.add(msg);
  }

  private Set processCommitTransactionMessage(RelayedCommitTransactionMessage commitMessage) {
    try {
      final TransactionBatchReader reader = batchReaderFactory.newTransactionBatchReader(commitMessage);
      ServerTransaction txn;
      // XXX:: Order has to be maintained.
      Map txns = new LinkedHashMap(reader.getNumTxns());
      while ((txn = reader.getNextTransaction()) != null) {
        txns.put(txn.getServerTransactionID(), txn);
      }
      rTxnManager.addCommitedTransactions(reader.getNodeID(), txns.keySet(), txns.values(), commitMessage);
      return txns.keySet();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private void doSyncObjectsResponse(ObjectSyncMessage syncMsg) {
    ServerTransaction txn = ServerTransactionFactory.createTxnFrom(syncMsg);
    rTxnManager.addObjectSyncTransaction(txn);
  }

  public void initialize(ConfigurationContext context) {
    super.initialize(context);
    ServerConfigurationContext oscc = (ServerConfigurationContext) context;
    this.batchReaderFactory = oscc.getTransactionBatchReaderFactory();
    this.rTxnManager = oscc.getL2Coordinator().getReplicatedTransactionManager();
    this.stateManager = oscc.getL2Coordinator().getStateManager();
    this.sendSink = oscc.getStage(ServerConfigurationContext.OBJECTS_SYNC_SEND_STAGE).getSink();
  }

}
