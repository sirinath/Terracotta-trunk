/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.handshakemanager;

import com.tc.async.api.EventContext;
import com.tc.async.api.Sink;
import com.tc.async.impl.NullSink;
import com.tc.logging.TCLogger;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.net.ServerID;
import com.tc.net.protocol.tcm.ChannelID;
import com.tc.net.protocol.transport.ConnectionID;
import com.tc.object.lockmanager.api.LockContext;
import com.tc.object.lockmanager.api.TryLockContext;
import com.tc.object.lockmanager.api.WaitContext;
import com.tc.object.msg.ClientHandshakeMessage;
import com.tc.object.msg.ObjectIDBatchRequest;
import com.tc.object.net.DSOChannelManager;
import com.tc.objectserver.l1.api.ClientStateManager;
import com.tc.objectserver.lockmanager.api.LockManager;
import com.tc.objectserver.tx.ServerTransactionManager;
import com.tc.util.SequenceValidator;
import com.tc.util.TCTimer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TimerTask;

public class ServerClientHandshakeManager {

  private static final State             INIT                              = new State("INIT");
  private static final State             STARTING                          = new State("STARTING");
  private static final State             STARTED                           = new State("STARTED");
  private static final int               BATCH_SEQUENCE_SIZE               = 10000;

  public static final Sink               NULL_SINK                         = new NullSink();

  private State                          state                             = INIT;

  private final TCTimer                  timer;
  private final ReconnectTimerTask       reconnectTimerTask;
  private final ClientStateManager       clientStateManager;
  private final LockManager              lockManager;
  private final Sink                     lockResponseSink;
  private final Sink                     oidRequestSink;
  private final long                     reconnectTimeout;
  private final DSOChannelManager        channelManager;
  private final TCLogger                 logger;
  private final SequenceValidator        sequenceValidator;
  private final Set                      existingUnconnectedClients        = new HashSet();
  private final Set                      clientsRequestingObjectIDSequence = new HashSet();
  private final boolean                  persistent;
  private final ServerTransactionManager transactionManager;
  private final TCLogger                 consoleLogger;
  private final ServerID                 serverNodeID;

  public ServerClientHandshakeManager(TCLogger logger, DSOChannelManager channelManager,
                                      ServerTransactionManager transactionManager, SequenceValidator sequenceValidator,
                                      ClientStateManager clientStateManager, LockManager lockManager,
                                      Sink lockResponseSink, Sink oidRequestSink, TCTimer timer, long reconnectTimeout,
                                      boolean persistent, TCLogger consoleLogger, ServerID serverNodeID) {
    this.logger = logger;
    this.channelManager = channelManager;
    this.transactionManager = transactionManager;
    this.sequenceValidator = sequenceValidator;
    this.clientStateManager = clientStateManager;
    this.lockManager = lockManager;
    this.lockResponseSink = lockResponseSink;
    this.oidRequestSink = oidRequestSink;
    this.reconnectTimeout = reconnectTimeout;
    this.timer = timer;
    this.persistent = persistent;
    this.consoleLogger = consoleLogger;
    this.reconnectTimerTask = new ReconnectTimerTask(this, timer);
    this.serverNodeID = serverNodeID;
  }

  public synchronized boolean isStarting() {
    return state == STARTING;
  }

  public synchronized boolean isStarted() {
    return state == STARTED;
  }

  public void notifyClientConnect(ClientHandshakeMessage handshake) throws ClientHandshakeException {
    ClientID clientID = (ClientID) handshake.getSourceNodeID();
    logger.info("Client connected " + clientID);
    synchronized (this) {
      logger.debug("Handling client handshake...");
      clientStateManager.startupNode(clientID);
      if (state == STARTED) {
        if (handshake.getObjectIDs().size() > 0) {
          //
          throw new ClientHandshakeException(
                                             "Clients connected after startup should have no existing object references.");
        }
        if (handshake.getWaitContexts().size() > 0) {
          //
          throw new ClientHandshakeException("Clients connected after startup should have no existing wait contexts.");
        }
        if (!handshake.getResentTransactionIDs().isEmpty()) {
          //
          throw new ClientHandshakeException("Clients connected after startup should not resend transactions.");
        }
        if (handshake.isObjectIDsRequested()) {
          clientsRequestingObjectIDSequence.add(clientID);
        }
        // XXX: It would be better to not have two different code paths that both call sendAckMessageFor(..)
        sendAckMessageFor(clientID);
        return;
      }

      if (state == STARTING) {
        channelManager.makeChannelActiveNoAck(handshake.getChannel());
        transactionManager.setResentTransactionIDs(clientID, handshake.getResentTransactionIDs());
      }

      this.sequenceValidator.initSequence(clientID, handshake.getTransactionSequenceIDs());

      clientStateManager.addReferences(clientID, handshake.getObjectIDs());

      for (Iterator i = handshake.getLockContexts().iterator(); i.hasNext();) {
        LockContext ctxt = (LockContext) i.next();
        lockManager.reestablishLock(ctxt.getLockID(), ctxt.getNodeID(), ctxt.getThreadID(), ctxt.getLockLevel(),
                                    lockResponseSink);
      }

      for (Iterator i = handshake.getWaitContexts().iterator(); i.hasNext();) {
        WaitContext ctxt = (WaitContext) i.next();
        lockManager.reestablishWait(ctxt.getLockID(), ctxt.getNodeID(), ctxt.getThreadID(), ctxt.getLockLevel(), ctxt
            .getTimerSpec(), lockResponseSink);
      }

      for (Iterator i = handshake.getPendingLockContexts().iterator(); i.hasNext();) {
        LockContext ctxt = (LockContext) i.next();
        lockManager.requestLock(ctxt.getLockID(), ctxt.getNodeID(), ctxt.getThreadID(), ctxt.getLockLevel(), ctxt
            .getLockType(), lockResponseSink);
      }

      for (Iterator i = handshake.getPendingTryLockContexts().iterator(); i.hasNext();) {
        TryLockContext ctxt = (TryLockContext) i.next();
        lockManager.tryRequestLock(ctxt.getLockID(), ctxt.getNodeID(), ctxt.getThreadID(), ctxt.getLockLevel(), ctxt
            .getLockType(), ctxt.getTimerSpec(), lockResponseSink);
      }

      if (handshake.isObjectIDsRequested()) {
        clientsRequestingObjectIDSequence.add(clientID);
      }

      if (state == STARTING) {
        logger.debug("Removing client " + clientID + " from set of existing unconnected clients.");
        existingUnconnectedClients.remove(clientID);
        if (existingUnconnectedClients.isEmpty()) {
          logger.debug("Last existing unconnected client (" + clientID + ") now connected.  Cancelling timer");
          timer.cancel();
          start();
        }
      } else {
        sendAckMessageFor(clientID);
      }
    }
  }

  private void sendAckMessageFor(ClientID clientID) {
    logger.debug("Sending handshake acknowledgement to " + clientID);

    // NOTE: handshake ack message initialize()/send() must be done atomically with making the channel active
    // and is thus done inside this channel manager call
    channelManager.makeChannelActive(clientID, persistent, serverNodeID);

    if (clientsRequestingObjectIDSequence.remove(clientID)) {
      oidRequestSink.add(new ObjectIDBatchRequestImpl(clientID, BATCH_SEQUENCE_SIZE));
    }
  }

  public synchronized void notifyTimeout() {
    if (!isStarted()) {
      logger
          .info("Reconnect window closing.  Killing any previously connected clients that failed to connect in time: "
                + existingUnconnectedClients);
      this.channelManager.closeAll(existingUnconnectedClients);
      for (Iterator i = existingUnconnectedClients.iterator(); i.hasNext();) {
        ClientID deadClient = (ClientID) i.next();
        this.clientStateManager.shutdownNode(deadClient);
        i.remove();
      }
      logger.info("Reconnect window closed. All dead clients removed.");
      start();
    } else {
      logger.info("Reconnect window closed, but server already started.");
    }
  }

  // Should be called from within the sync block
  private void start() {
    logger.info("Starting DSO services...");
    lockManager.start();
    Set cids = Collections.unmodifiableSet(channelManager.getAllClientIDs());
    transactionManager.start(cids);
    for (Iterator i = cids.iterator(); i.hasNext();) {
      ClientID clientID = (ClientID) i.next();
      sendAckMessageFor(clientID);
    }
    state = STARTED;
  }

  public synchronized void setStarting(Set existingConnections) {
    assertInit();
    state = STARTING;
    if (existingConnections.isEmpty()) {
      start();
    } else {
      for (Iterator i = existingConnections.iterator(); i.hasNext();) {
        existingUnconnectedClients.add(channelManager.getClientIDFor(new ChannelID(((ConnectionID) i.next())
            .getChannelID())));
      }

      consoleLogger.info("Starting reconnect window: " + this.reconnectTimeout + " ms.");
      timer.schedule(reconnectTimerTask, this.reconnectTimeout);
    }
  }

  private void assertInit() {
    if (state != INIT) throw new AssertionError("Should be in STARTING state: " + state);
  }

  /**
   * Notifies handshake manager that the reconnect time has passed.
   * 
   * @author orion
   */
  private static class ReconnectTimerTask extends TimerTask {

    private final TCTimer                      timer;
    private final ServerClientHandshakeManager handshakeManager;

    private ReconnectTimerTask(ServerClientHandshakeManager handshakeManager, TCTimer timer) {
      this.handshakeManager = handshakeManager;
      this.timer = timer;
    }

    public void run() {
      timer.cancel();
      handshakeManager.notifyTimeout();
    }

  }

  private static class State {
    private final String name;

    private State(String name) {
      this.name = name;
    }

    public String toString() {
      return getClass().getName() + "[" + name + "]";
    }
  }

  private static class ObjectIDBatchRequestImpl implements ObjectIDBatchRequest, EventContext {

    private final NodeID clientID;
    private final int    batchSize;

    public ObjectIDBatchRequestImpl(NodeID clientID, int batchSize) {
      this.clientID = clientID;
      this.batchSize = batchSize;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public NodeID getRequestingNodeID() {
      return clientID;
    }

  }

}
