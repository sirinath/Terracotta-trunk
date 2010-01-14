/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import EDU.oswego.cs.dl.util.concurrent.SynchronizedBoolean;

import com.tc.exception.TCInternalError;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.TCLogging;
import com.tc.net.CommStackMismatchException;
import com.tc.net.MaxConnectionsExceededException;
import com.tc.net.core.TCConnection;
import com.tc.net.core.event.TCConnectionEvent;
import com.tc.net.protocol.NetworkLayer;
import com.tc.net.protocol.NetworkStackID;
import com.tc.net.protocol.TCNetworkMessage;
import com.tc.net.protocol.TCProtocolAdaptor;
import com.tc.util.Assert;
import com.tc.util.TCTimeoutException;
import com.tc.util.concurrent.TCExceptionResultException;
import com.tc.util.concurrent.TCFuture;

import java.io.IOException;
import java.util.List;

/**
 * Client implementation of the transport network layer.
 */
public class ClientMessageTransport extends MessageTransportBase {
  // it was 2 minutes timeout, reduce to 10 sec
  public static final long                  TRANSPORT_HANDSHAKE_SYNACK_TIMEOUT = 10000;
  private final ClientConnectionEstablisher connectionEstablisher;
  private boolean                           wasOpened                          = false;
  private TCFuture                          waitForSynAckResult;
  private final WireProtocolAdaptorFactory  wireProtocolAdaptorFactory;
  private final SynchronizedBoolean         isOpening                          = new SynchronizedBoolean(false);
  private final int                         callbackPort;

  /**
   * Constructor for when you want a transport that isn't connected yet (e.g., in a client). This constructor will
   * create an unopened MessageTransport.
   * 
   * @param commsManager CommmunicationsManager
   */
  public ClientMessageTransport(ClientConnectionEstablisher clientConnectionEstablisher,
                                TransportHandshakeErrorHandler handshakeErrorHandler,
                                TransportHandshakeMessageFactory messageFactory,
                                WireProtocolAdaptorFactory wireProtocolAdaptorFactory, int callbackPort) {

    super(MessageTransportState.STATE_START, handshakeErrorHandler, messageFactory, false, TCLogging
        .getLogger(ClientMessageTransport.class));
    this.wireProtocolAdaptorFactory = wireProtocolAdaptorFactory;
    this.connectionEstablisher = clientConnectionEstablisher;
    this.callbackPort = callbackPort;
  }

  /**
   * Blocking open. Causes a connection to be made. Will throw exceptions if the connect fails.
   * 
   * @throws TCTimeoutException
   * @throws IOException
   * @throws TCTimeoutException
   * @throws MaxConnectionsExceededException
   */
  public NetworkStackID open() throws TCTimeoutException, IOException, MaxConnectionsExceededException,
      CommStackMismatchException {
    // XXX: This extra boolean flag is dumb, but it's here because the close event can show up
    // while the lock on isOpen is held here. That will cause a deadlock because the close event is thrown on the
    // comms thread which means that the handshake messages can't be sent.
    // The state machine here needs to be rationalized.
    isOpening.set(true);
    synchronized (isOpen) {
      Assert.eval("can't open an already open transport", !isOpen.get());
      try {
        wireNewConnection(connectionEstablisher.open(this));
        HandshakeResult result = handShake();
        handleHandshakeError(result);
        sendAck();
        Assert.eval(!this.connectionId.isNull());
        isOpen.set(true);
        NetworkStackID nid = new NetworkStackID(this.connectionId.getChannelID());
        wasOpened = true;
        return (nid);
      } catch (TCTimeoutException e) {
        // DEV-1320
        clearConnection();
        status.reset();
        throw e;
      } catch (IOException e) {
        clearConnection();
        status.reset();
        throw e;
      } finally {
        isOpening.set(false);
      }
    }
  }

  private void handleHandshakeError(HandshakeResult result) throws IOException, MaxConnectionsExceededException,
      CommStackMismatchException {
    if (result.hasErrorContext()) {
      switch (result.getErrorType()) {
        case TransportHandshakeError.ERROR_MAX_CONNECTION_EXCEED:
          cleanConnectionWithoutNotifyListeners();
          throw new MaxConnectionsExceededException(getMaxConnectionsExceededMessage(result.maxConnections()));
        case TransportHandshakeError.ERROR_STACK_MISMATCH:
          cleanConnectionWithoutNotifyListeners();
          throw new CommStackMismatchException("Disconnect due to comm stack mismatch");
        default:
          throw new IOException("Disconnect due to transport handshake error");
      }
    }
  }

  /*
   * Do not trigger reconnection
   */
  private void cleanConnectionWithoutNotifyListeners() {
    List tl = this.getTransportListeners();
    this.removeTransportListeners();
    clearConnection();
    this.addTransportListeners(tl);
    status.reset();
  }

  /**
   * Returns true if the MessageTransport was ever in an open state.
   */
  public boolean wasOpened() {
    return wasOpened;
  }

  public boolean isNotOpen() {
    return !isOpening.get() && !isOpen.get();
  }

  // TODO :: come back
  public void closeEvent(TCConnectionEvent event) {
    if (isNotOpen()) return;
    super.closeEvent(event);
  }

  protected void receiveTransportMessageImpl(WireProtocolMessage message) {
    synchronized (status) {
      if (status.isSynSent()) {
        handleSynAck(message);
        message.recycle();
        return;
      }

      if (!status.isEstablished()) {
        logger.warn("Ignoring the message received for an Un-Established Connection; " + message.getSource() + "; "
                    + message);
        message.recycle();
        return;
      }
    }
    super.receiveToReceiveLayer(message);
  }

  private void handleSynAck(WireProtocolMessage message) {
    if (!verifySynAck(message)) {
      handleHandshakeError(new TransportHandshakeErrorContext(
                                                              "Received a message that was not a SYN_ACK while waiting for SYN_ACK: "
                                                                  + message, TransportHandshakeError.ERROR_HANDSHAKE));
    } else {
      SynAckMessage synAck = (SynAckMessage) message;
      if (synAck.hasErrorContext()) {
        // if stack is mismatched then get the client side communication stack
        // and append in the error message
        if (synAck.getErrorType() == TransportHandshakeError.ERROR_STACK_MISMATCH) {
          String errorMessage = "\n\nLayers Present in Client side communication stack: ";
          // get the names of stack layers present
          errorMessage += getCommunicationStackNames(this);

          errorMessage = "\nTHERE IS A MISMATCH IN THE COMMUNICATION STACKS\n" + synAck.getErrorContext()
                         + errorMessage;

          if ((getCommunicationStackFlags(this) & NetworkLayer.TYPE_OOO_LAYER) != 0) {
            logger.error(NetworkLayer.ERROR_OOO_IN_CLIENT_NOT_IN_SERVER);
            errorMessage = "\n\n" + NetworkLayer.ERROR_OOO_IN_CLIENT_NOT_IN_SERVER + errorMessage;
          } else {
            logger.error(NetworkLayer.ERROR_OOO_IN_SERVER_NOT_IN_CLIENT);
            errorMessage = "\n\n" + NetworkLayer.ERROR_OOO_IN_SERVER_NOT_IN_CLIENT + errorMessage;
          }
          handleHandshakeError(new TransportHandshakeErrorContext(errorMessage + "\n\nPLEASE RECONFIGURE THE STACKS",
                                                                  TransportHandshakeError.ERROR_STACK_MISMATCH));
        } else {
          handleHandshakeError(new TransportHandshakeErrorContext(synAck.getErrorContext() + message,
                                                                  TransportHandshakeError.ERROR_GENERIC));
        }
      }

      if (!connectionId.isNewConnection()) {
        // This is a reconnect
        Assert.eval(connectionId.equals(synAck.getConnectionId()));
      }
      if (!synAck.isMaxConnectionsExceeded()) {
        this.connectionId = synAck.getConnectionId();

        Assert.assertNotNull("Connection id from the server was null!", this.connectionId);
        Assert.eval(!ConnectionID.NULL_ID.equals(this.connectionId));
        Assert.assertNotNull(this.waitForSynAckResult);
      }
      getConnection().addWeight(MessageTransport.CONNWEIGHT_TX_HANDSHAKED);
      getConnection().setTransportEstablished();
      this.waitForSynAckResult.set(synAck);
      setRemoteCallbackPort(synAck.getCallbackPort());
    }
    return;
  }

  private boolean verifySynAck(TCNetworkMessage message) {
    // XXX: yuck.
    return message instanceof TransportHandshakeMessage && ((TransportHandshakeMessage) message).isSynAck();
  }

  /**
   * Builds a protocol stack and tries to make a connection. This is a blocking call.
   * 
   * @throws TCTimeoutException
   * @throws MaxConnectionsExceededException
   * @throws IOException
   */
  HandshakeResult handShake() throws TCTimeoutException {
    sendSyn();
    SynAckMessage synAck = waitForSynAck();
    return new HandshakeResult(synAck);
  }

  private SynAckMessage waitForSynAck() throws TCTimeoutException {
    try {
      SynAckMessage synAck = (SynAckMessage) waitForSynAckResult.get(TRANSPORT_HANDSHAKE_SYNACK_TIMEOUT);
      return synAck;
    } catch (InterruptedException e) {
      throw new TCRuntimeException(e);
    } catch (TCExceptionResultException e) {
      throw new TCInternalError(e);
    }
  }

  private void sendSyn() {
    synchronized (status) {
      if (status.isEstablished() || status.isSynSent()) { throw new AssertionError(" ERROR !!! " + status); }
      waitForSynAckResult = new TCFuture(status);
      // get the stack layer list and pass it in
      short stackLayerFlags = getCommunicationStackFlags(this);
      TransportHandshakeMessage syn = this.messageFactory.createSyn(this.connectionId, getConnection(),
                                                                    stackLayerFlags, callbackPort);
      // send syn message
      this.sendToConnection(syn);
      this.status.synSent();
    }
  }

  private void sendAck() throws IOException {
    synchronized (status) {
      // DEV-1364 : Connection close might have happened
      if (!status.isSynSent()) throw new IOException();
      TransportHandshakeMessage ack = this.messageFactory.createAck(this.connectionId, getConnection());
      // send ack message
      this.sendToConnection(ack);
      this.status.established();
    }
    fireTransportConnectedEvent();
  }

  void reconnect(TCConnection connection) throws Exception {

    // don't do reconnect if open is still going on
    if (!wasOpened) {
      logger.warn("Transport was opened already. Skip reconnect " + connection);
      return;
    }

    Assert.eval(!isConnected());
    wireNewConnection(connection);
    try {
      HandshakeResult result = handShake();
      // DEV-2781 - check for MaxConnectionsExceeded message from server during reconnects
      if (result.isMaxConnectionsExceeded()) {
        close();
        throw new MaxConnectionsExceededException(getMaxConnectionsExceededMessage(result.maxConnections()));
      }
      sendAck();
    } catch (Exception t) {
      status.reset();
      throw t;
    }
  }

  private String getMaxConnectionsExceededMessage(int maxConnections) {
    return "Your product key only allows maximum " + maxConnections + " clients to connect.";
  }

  TCProtocolAdaptor getProtocolAdapter() {
    return wireProtocolAdaptorFactory.newWireProtocolAdaptor(new WireProtocolMessageSink() {
      public void putMessage(WireProtocolMessage message) {
        receiveTransportMessage(message);
      }
    });
  }

  void endIfDisconnected() {
    synchronized (this.status) {
      if (!this.isConnected() && !this.status.isEnd()) {
        this.status.end();
      }
    }
  }

  private static final class HandshakeResult {
    private final SynAckMessage synAck;

    private HandshakeResult(SynAckMessage synAck) {
      this.synAck = synAck;
    }

    public int maxConnections() {
      return this.synAck.getMaxConnections();
    }

    public boolean isMaxConnectionsExceeded() {
      return this.synAck.isMaxConnectionsExceeded();
    }

    public boolean hasErrorContext() {
      return this.synAck.isMaxConnectionsExceeded() || this.synAck.hasErrorContext();
    }

    public short getErrorType() {
      if (this.synAck.isMaxConnectionsExceeded()) {
        return TransportHandshakeError.ERROR_MAX_CONNECTION_EXCEED;
      } else {
        return this.synAck.getErrorType();
      }
    }

  }

  public ClientConnectionEstablisher getConnectionEstablisher() {
    return connectionEstablisher;
  }
}
