/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import EDU.oswego.cs.dl.util.concurrent.ConcurrentHashMap;

import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.net.core.TCConnection;
import com.tc.net.protocol.IllegalReconnectException;
import com.tc.net.protocol.NetworkLayer;
import com.tc.net.protocol.NetworkStackHarness;
import com.tc.net.protocol.NetworkStackHarnessFactory;
import com.tc.net.protocol.ProtocolAdaptorFactory;
import com.tc.net.protocol.StackNotFoundException;
import com.tc.net.protocol.TCProtocolAdaptor;
import com.tc.net.protocol.tcm.ChannelID;
import com.tc.net.protocol.tcm.ServerMessageChannelFactory;
import com.tc.util.Assert;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides network stacks on the server side
 */
public class ServerStackProvider implements NetworkStackProvider, MessageTransportListener, ProtocolAdaptorFactory {

  private final Map                              harnesses          = new ConcurrentHashMap();
  private final NetworkStackHarnessFactory       harnessFactory;
  private final ServerMessageChannelFactory      channelFactory;
  private final TransportHandshakeMessageFactory handshakeMessageFactory;
  private final ConnectionIDFactory              connectionIdFactory;
  private final ConnectionPolicy                 connectionPolicy;
  private final WireProtocolAdaptorFactory       wireProtocolAdaptorFactory;
  private final WireProtocolMessageSink          wireProtoMsgsink;
  private final MessageTransportFactory          messageTransportFactory;
  private final List                             transportListeners = new ArrayList(1);
  private final TCLogger                         logger;
  private final TCLogger                         consoleLogger      = CustomerLogging.getConsoleLogger();

  public ServerStackProvider(TCLogger logger, Set initialConnectionIDs, NetworkStackHarnessFactory harnessFactory,
                             ServerMessageChannelFactory channelFactory,
                             MessageTransportFactory messageTransportFactory,
                             TransportHandshakeMessageFactory handshakeMessageFactory,
                             ConnectionIDFactory connectionIdFactory, ConnectionPolicy connectionPolicy,
                             WireProtocolAdaptorFactory wireProtocolAdaptorFactory) {
    this(logger, initialConnectionIDs, harnessFactory, channelFactory, messageTransportFactory,
         handshakeMessageFactory, connectionIdFactory, connectionPolicy, wireProtocolAdaptorFactory, null);
  }

  public ServerStackProvider(TCLogger logger, Set initialConnectionIDs, NetworkStackHarnessFactory harnessFactory,
                             ServerMessageChannelFactory channelFactory,
                             MessageTransportFactory messageTransportFactory,
                             TransportHandshakeMessageFactory handshakeMessageFactory,
                             ConnectionIDFactory connectionIdFactory, ConnectionPolicy connectionPolicy,
                             WireProtocolAdaptorFactory wireProtocolAdaptorFactory,
                             WireProtocolMessageSink wireProtoMsgSink) {
    this.messageTransportFactory = messageTransportFactory;
    this.connectionPolicy = connectionPolicy;
    this.wireProtocolAdaptorFactory = wireProtocolAdaptorFactory;
    this.wireProtoMsgsink = wireProtoMsgSink;
    Assert.assertNotNull(harnessFactory);
    this.harnessFactory = harnessFactory;
    this.channelFactory = channelFactory;
    this.handshakeMessageFactory = handshakeMessageFactory;
    this.connectionIdFactory = connectionIdFactory;
    this.transportListeners.add(this);
    this.logger = logger;
    for (Iterator i = initialConnectionIDs.iterator(); i.hasNext();) {
      ConnectionID connectionID = (ConnectionID) i.next();
      logger.info("Preparing comms stack for previously connected client: " + connectionID);
      newStackHarness(connectionID, messageTransportFactory.createNewTransport(connectionID,
                                                                               createHandshakeErrorHandler(),
                                                                               handshakeMessageFactory,
                                                                               transportListeners));
    }
  }

  public MessageTransport attachNewConnection(ConnectionID connectionId, TCConnection connection)
      throws StackNotFoundException, IllegalReconnectException {
    Assert.assertNotNull(connection);

    final NetworkStackHarness harness;
    final MessageTransport rv;
    if (connectionId.isNewConnection()) {
      if (connectionId.getChannelID() == ChannelID.NULL_ID.toLong()) {
        connectionId = connectionIdFactory.nextConnectionId();
      } else {
        connectionId = connectionIdFactory.makeConnectionId(connectionId.getChannelID());
      }

      rv = messageTransportFactory.createNewTransport(connectionId, connection, createHandshakeErrorHandler(),
                                                      handshakeMessageFactory, transportListeners);
      newStackHarness(connectionId, rv);
    } else {
      harness = (NetworkStackHarness) harnesses.get(connectionId);

      if (harness == null) {
        throw new StackNotFoundException(connectionId, connection.getRemoteAddress());
      } else {
        rv = harness.attachNewConnection(connection);
        connectionIdFactory.restoreConnectionId(connectionId);
      }
    }

    return rv;
  }

  private void newStackHarness(ConnectionID id, MessageTransport transport) {
    final NetworkStackHarness harness;
    harness = harnessFactory.createServerHarness(channelFactory, transport, new MessageTransportListener[] { this });
    harness.finalizeStack();
    Object previous = harnesses.put(id, harness);
    if (previous != null) { throw new AssertionError("previous is " + previous); }
  }

  private TransportHandshakeErrorHandler createHandshakeErrorHandler() {
    return new TransportHandshakeErrorHandler() {

      public void handleHandshakeError(TransportHandshakeErrorContext e) {
        consoleLogger.info(e.getMessage());
        logger.info(e.getMessage());
      }

      public void handleHandshakeError(TransportHandshakeErrorContext e, TransportHandshakeMessage m) {
        logger.info(e.getMessage());
      }

    };
  }

  NetworkStackHarness removeNetworkStack(ConnectionID connectionId) {
    return (NetworkStackHarness) harnesses.remove(connectionId);
  }

  /*********************************************************************************************************************
   * MessageTransportListener methods.
   */
  public void notifyTransportConnected(MessageTransport transport) {
    // don't care
  }

  /**
   * A client disconnected.
   */
  public void notifyTransportDisconnected(MessageTransport transport) {
    /*
     * Even for temporary disconnects, we need to keep proper accounting. Otherwise, the same client reconnect may fail.
     */
    this.connectionPolicy.clientDisconnected(transport.getConnectionId());
  }

  private void close(ConnectionID connectionId) {
    NetworkStackHarness harness = removeNetworkStack(connectionId);
    if (harness == null) { throw new AssertionError(
                                                    "Receive a transport closed event for a transport that isn't in the map :"
                                                        + connectionId); }
  }

  public void notifyTransportConnectAttempt(MessageTransport transport) {
    // don't care
  }

  /**
   * The connection was closed. The client is never allowed to reconnect. Removes stack associated with the given
   * transport from the map of managed stacks.
   */
  public void notifyTransportClosed(MessageTransport transport) {
    close(transport.getConnectionId());
    this.connectionPolicy.clientDisconnected(transport.getConnectionId());
  }

  /*********************************************************************************************************************
   * ProtocolAdaptorFactory interface
   */

  public TCProtocolAdaptor getInstance() {
    if (wireProtoMsgsink != null) {
      return this.wireProtocolAdaptorFactory.newWireProtocolAdaptor(wireProtoMsgsink);
    } else {
      MessageSink sink = new MessageSink(createHandshakeErrorHandler());
      return this.wireProtocolAdaptorFactory.newWireProtocolAdaptor(sink);
    }
  }

  /*********************************************************************************************************************
   * private stuff
   */

  class MessageSink implements WireProtocolMessageSink {
    private final TransportHandshakeErrorHandler handshakeErrorHandler;
    private volatile boolean                     isSynReceived    = false;
    private volatile boolean                     isHandshakeError = false;
    private volatile MessageTransport            transport;

    private MessageSink(TransportHandshakeErrorHandler handshakeErrorHandler) {
      this.handshakeErrorHandler = handshakeErrorHandler;
    }

    public void putMessage(WireProtocolMessage message) {
      if (!isSynReceived) {
        synchronized (this) {
          if (!isSynReceived) {
            isSynReceived = verifyAndHandleSyn(message);
            message.recycle();
            return;
          }
        }
      }
      if (!isHandshakeError) {
        this.transport.receiveTransportMessage(message);
      }
    }

    private boolean verifyAndHandleSyn(WireProtocolMessage message) {
      boolean isSynced = false;
      if (!verifySyn(message)) {
        handleHandshakeError(new TransportHandshakeErrorContext("Expected a SYN message but received: " + message,
                                                                TransportHandshakeError.ERROR_HANDSHAKE));
      } else {
        try {
          handleSyn((SynMessage) message);
          isSynced = true;
        } catch (StackNotFoundException e) {
          handleHandshakeError(new TransportHandshakeErrorContext(
                                                                  "Client Cannot Reconnect "
                                                                      + e.getMessage()
                                                                      + " Restart the client to allow it to rejoin the cluster."
                                                                      + " Many client reconnection failures can be avoided by"
                                                                      + " configuring the Terracotta server array for \"permanent-store\""
                                                                      + " and tuning reconnection parameters. For more information,"
                                                                      + " see http://www.terracotta.org/ha", e));
        }
      }
      return isSynced;
    }

    private void handleHandshakeError(TransportHandshakeErrorContext ctxt) {
      this.isHandshakeError = true;
      this.handshakeErrorHandler.handleHandshakeError(ctxt);
    }

    private void handleSyn(SynMessage syn) throws StackNotFoundException {
      ConnectionID connectionId = syn.getConnectionId();

      if (connectionId == null) {
        sendSynAck(connectionId,
                   new TransportHandshakeErrorContext("Invalid connection id: " + connectionId,
                                                      TransportHandshakeError.ERROR_INVALID_CONNECTION_ID), syn
                       .getSource());
        this.isHandshakeError = true;
        return;
      }

      try {
        this.transport = attachNewConnection(connectionId, syn.getSource());
      } catch (IllegalReconnectException e) {
        logger.warn("Client attempting an illegal reconnect for id " + connectionId + ", " + syn.getSource());
        return;
      }

      this.transport.setRemoteCallbackPort(syn.getCallbackPort());

      // now check that the client side stack and server side stack are both in sync

      // get the client side stack layer
      short clientStackLayerFlags = syn.getStackLayerFlags();
      // get the server side stack layer
      short serverStackLayerFlags = this.transport.getCommunicationStackFlags(this.transport);

      // compare the two and send an error if there is a mismatch
      // send the layers present at the server side in the error message
      if (clientStackLayerFlags != serverStackLayerFlags) {
        String layersPresentInServer = "Layers Present in Server side communication stack: ";
        // get the names of stack layers present
        layersPresentInServer += this.transport.getCommunicationStackNames(this.transport);
        // send the SynAck with the error
        sendSynAck(connectionId, new TransportHandshakeErrorContext(layersPresentInServer,
                                                                    TransportHandshakeError.ERROR_STACK_MISMATCH), syn
            .getSource());

        if ((serverStackLayerFlags & NetworkLayer.TYPE_OOO_LAYER) != 0) logger
            .error(NetworkLayer.ERROR_OOO_IN_SERVER_NOT_IN_CLIENT);
        else logger.error(NetworkLayer.ERROR_OOO_IN_CLIENT_NOT_IN_SERVER);
        this.isHandshakeError = true;
        return;
      }
      connectionId = this.transport.getConnectionId();
      sendSynAck(connectionId, syn.getSource());
    }

    private boolean verifySyn(WireProtocolMessage message) {
      return message instanceof TransportHandshakeMessage && (((TransportHandshakeMessage) message).isSyn());
    }

    private void sendSynAck(ConnectionID connectionId, TCConnection source) {
      source.addWeight(MessageTransport.CONNWEIGHT_TX_HANDSHAKED);
      sendSynAck(connectionId, null, source);
    }

    private void sendSynAck(ConnectionID connectionId, TransportHandshakeErrorContext errorContext, TCConnection source) {
      TransportHandshakeMessage synAck;
      boolean isError = (errorContext != null);
      int maxConnections = connectionPolicy.getMaxConnections();
      boolean isMaxConnectionsReached = false;
      // clients after max connections are not added. clients anyway close after seeing max connection reached
      // message. Server on getting the close event from these clients, removes the stack harness.
      isMaxConnectionsReached = connectionPolicy.connectClient(connectionId) ? false : true;
      if (isError) {
        synAck = handshakeMessageFactory.createSynAck(connectionId, errorContext, source, isMaxConnectionsReached,
                                                      maxConnections);
      } else {
        int callbackPort = source.getLocalAddress().getPort();
        synAck = handshakeMessageFactory.createSynAck(connectionId, source, isMaxConnectionsReached, maxConnections,
                                                      callbackPort);
      }
      sendMessage(synAck);
    }

    private void sendMessage(WireProtocolMessage message) {
      transport.sendToConnection(message);
    }
  }

}
