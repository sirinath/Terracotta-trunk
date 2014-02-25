/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventContext;
import com.tc.async.api.Sink;
import com.tc.exception.TCClassNotFoundException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.NodeID;
import com.tc.object.ClientConfigurationContext;
import com.tc.object.bytecode.ManagerUtil;
import com.tc.object.context.ServerEventDeliveryContext;
import com.tc.object.dmi.DmiDescriptor;
import com.tc.object.dna.api.LogicalChangeID;
import com.tc.object.dna.api.LogicalChangeResult;
import com.tc.object.event.DmiEventContext;
import com.tc.object.event.DmiManager;
import com.tc.object.gtx.ClientGlobalTransactionManager;
import com.tc.object.gtx.GlobalTransactionID;
import com.tc.object.locks.ClientLockManager;
import com.tc.object.locks.ClientServerExchangeLockContext;
import com.tc.object.msg.AcknowledgeTransactionMessage;
import com.tc.object.msg.AcknowledgeTransactionMessageFactory;
import com.tc.object.msg.BroadcastTransactionMessage;
import com.tc.object.msg.BroadcastTransactionMessageImpl;
import com.tc.object.session.SessionManager;
import com.tc.object.tx.ClientTransactionManager;
import com.tc.server.ServerEvent;
import com.tc.util.concurrent.ThreadUtil;
import com.tcclient.object.DistributedMethodCall;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * @author steve
 */
public class ReceiveTransactionHandler extends AbstractEventHandler {

  private static final TCLogger logger = TCLogging.getLogger(ReceiveTransactionHandler.class);

  private ClientTransactionManager txManager;
  private ClientLockManager lockManager;
  private final SessionManager sessionManager;
  private final ClientGlobalTransactionManager gtxManager;
  private final AcknowledgeTransactionMessageFactory atmFactory;
  private final Sink dmiSink;
  private final DmiManager dmiManager;
  private final CountDownLatch testStartLatch;
  private final Sink eventDeliverySink;

  private volatile boolean clientInitialized;

  public ReceiveTransactionHandler(final AcknowledgeTransactionMessageFactory atmFactory,
                                   final ClientGlobalTransactionManager gtxManager,
                                   final SessionManager sessionManager,
                                   final Sink dmiSink,
                                   final DmiManager dmiManager,
                                   final CountDownLatch testStartLatch,
                                   final Sink eventDeliverySink) {
    this.atmFactory = atmFactory;
    this.gtxManager = gtxManager;
    this.sessionManager = sessionManager;
    this.dmiSink = dmiSink;
    this.dmiManager = dmiManager;
    this.testStartLatch = testStartLatch;
    this.eventDeliverySink = eventDeliverySink;
  }

  @Override
  public void handleEvent(EventContext context) {
    final BroadcastTransactionMessageImpl btm = (BroadcastTransactionMessageImpl) context;
    final List dmis = btm.getDmiDescriptors();

    if (dmis.size() > 0) {
      waitForClientInitialized();
    }

    final GlobalTransactionID lowWaterMark = btm.getLowGlobalTransactionIDWatermark();
    if (!lowWaterMark.isNull()) {
      this.gtxManager.setLowWatermark(lowWaterMark, btm.getSourceNodeID());
    }

    if (this.gtxManager.startApply(btm.getCommitterID(), btm.getTransactionID(), btm.getGlobalTransactionID(),
        btm.getSourceNodeID())) {
      final Collection changes = btm.getObjectChanges();
      if (changes.size() > 0 || btm.getNewRoots().size() > 0) {
        try {
          this.txManager.apply(btm.getTransactionType(), btm.getLockIDs(), changes, btm.getNewRoots());
        } catch (TCClassNotFoundException cnfe) {
          logger.warn("transaction apply failed for " + btm.getTransactionID(), cnfe);
          // Do not ignore, re-throw to kill this L1
          throw cnfe;
        }

      }

      sendDmis(dmis);
      notifyLogicalChangeResultsReceived(btm);
      sendServerEvents(btm);
    }

    notifyLockManager(btm);
    sendAck(btm);
    btm.recycle();
  }

  void sendAck(final BroadcastTransactionMessage btm) {
    // XXX:: This is a potential race condition here 'coz after we decide to send an ACK
    // and before we actually send it, the server may go down and come back up !
    if (this.sessionManager.isCurrentSession(btm.getSourceNodeID(), btm.getLocalSessionID())) {
      AcknowledgeTransactionMessage ack = this.atmFactory.newAcknowledgeTransactionMessage(btm.getSourceNodeID());
      ack.initialize(btm.getCommitterID(), btm.getTransactionID());
      ack.send();
    }
  }

  void notifyLockManager(final BroadcastTransactionMessage btm) {
    final Collection notifies = btm.getNotifies();
    for (final Object notify : notifies) {
      ClientServerExchangeLockContext lc = (ClientServerExchangeLockContext) notify;
      this.lockManager.notified(lc.getLockID(), lc.getThreadID());
    }
  }

  void notifyLogicalChangeResultsReceived(final BroadcastTransactionMessageImpl btm) {
    final Map<LogicalChangeID, LogicalChangeResult> logicalChangeResults = btm.getLogicalChangeResults();
    if (!logicalChangeResults.isEmpty()) {
      this.txManager.receivedLogicalChangeResult(logicalChangeResults);
    }
  }

  void sendDmis(final List dmis) {
    for (final Object dmi : dmis) {
      final DmiDescriptor dd = (DmiDescriptor) dmi;
      // NOTE: This prepare call must happen before handing off the DMI to the stage, and more
      // importantly before sending ACK below
      final DistributedMethodCall dmc = this.dmiManager.extract(dd);
      if (dmc != null) {
        this.dmiSink.add(new DmiEventContext(dmc));
      }
    }
  }

  void sendServerEvents(final BroadcastTransactionMessage btm) {
    final NodeID remoteNode = btm.getChannel().getRemoteNodeID();
    // unfold the batch and multiplex messages to different queues based on the event key
    for (final ServerEvent event : btm.getEvents()) {
      // blocks when the internal stage's queue reaches TCPropertiesConsts.L1_SERVER_EVENT_DELIVERY_QUEUE_SIZE
      // to delay the transaction acknowledgement and provide back-pressure on clients
      eventDeliverySink.add(new ServerEventDeliveryContext(event, remoteNode));
    }
  }

  private void waitForClientInitialized() {
    if (clientInitialized) { return; }

    while (!ManagerUtil.isManagerEnabled()) {
      logger.info("Waiting for manager initialization");
      ThreadUtil.reallySleep(1000);
    }

    if (testStartLatch != null) {
      try {
        testStartLatch.await();
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }

    clientInitialized = true;
  }

  @Override
  public void initialize(ConfigurationContext context) {
    super.initialize(context);
    ClientConfigurationContext ccc = (ClientConfigurationContext) context;
    this.txManager = ccc.getTransactionManager();
    this.lockManager = ccc.getLockManager();
  }
}
