/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;
import EDU.oswego.cs.dl.util.concurrent.SynchronizedRef;

import com.tc.exception.ImplementMe;
import com.tc.net.TCSocketAddress;
import com.tc.net.core.ConnectionAddressProvider;
import com.tc.net.core.ConnectionInfo;
import com.tc.net.core.MockConnectionManager;
import com.tc.net.core.MockTCConnection;
import com.tc.net.core.event.TCConnectionEvent;
import com.tc.net.protocol.PlainNetworkStackHarnessFactory;
import com.tc.net.protocol.tcm.CommunicationsManager;
import com.tc.net.protocol.tcm.CommunicationsManagerImpl;
import com.tc.net.protocol.tcm.NetworkListener;
import com.tc.net.protocol.tcm.NullMessageMonitor;
import com.tc.object.session.NullSessionManager;
import com.tc.test.TCTestCase;

import java.util.Collections;
import java.util.List;

/**
 * x normal connect and handshake o reconnect and handshake
 */
public class ClientMessageTransportTest extends TCTestCase {
  private ConnectionID                     connectionId;
  private ClientMessageTransport           transport;
  private MockConnectionManager            connectionManager;
  private MockTCConnection                 connection;
  private TransportHandshakeMessageFactory transportMessageFactory;
  private TransportHandshakeErrorHandler   handshakeErrorHandler;
  private int                              maxRetries = 10;

  public void setUp() {
    DefaultConnectionIdFactory connectionIDProvider = new DefaultConnectionIdFactory();
    this.connectionId = connectionIDProvider.nextConnectionId();
    this.connectionManager = new MockConnectionManager();
    this.connection = new MockTCConnection();
    this.connectionManager.setConnection(connection);
    this.transportMessageFactory = new TransportMessageFactoryImpl();
    handshakeErrorHandler = new TransportHandshakeErrorHandler() {

      public void handleHandshakeError(TransportHandshakeErrorContext e) {
        throw new ImplementMe();

      }

      public void handleHandshakeError(TransportHandshakeErrorContext e, TransportHandshakeMessage m) {
        throw new ImplementMe();

      }

    };
    final ConnectionInfo connectionInfo = new ConnectionInfo("", 0);
    ClientConnectionEstablisher cce = new ClientConnectionEstablisher(
                                                                      connectionManager,
                                                                      new ConnectionAddressProvider(
                                                                                                    new ConnectionInfo[] { connectionInfo }),
                                                                      maxRetries, 5000);
    transport = new ClientMessageTransport(cce, handshakeErrorHandler, this.transportMessageFactory,
                                           new WireProtocolAdaptorFactoryImpl());
  }

  public void testRoundRobinReconnect() throws Exception {
    SynchronizedRef errorRef = new SynchronizedRef(null);
    ClientHandshakeMessageResponder tester = new ClientHandshakeMessageResponder(new LinkedQueue(), new LinkedQueue(),
                                                                                 this.transportMessageFactory,
                                                                                 this.connectionId, this.transport,
                                                                                 errorRef);
    this.connection.setMessageSink(tester);

    transport.open();
    while (!connection.connectCalls.isEmpty()) {
      connection.connectCalls.take();
    }

    connection.fail = true;
    transport.closeEvent(new TCConnectionEvent(connection));

    // FIXME 2005-12-14 -- We should restore this test.
    // assertNull(connection.connectCalls.poll(3000));

  }

  public void testConnectAndHandshake() throws Exception {
    SynchronizedRef errorRef = new SynchronizedRef(null);
    ClientHandshakeMessageResponder tester = new ClientHandshakeMessageResponder(new LinkedQueue(), new LinkedQueue(),
                                                                                 this.transportMessageFactory,
                                                                                 this.connectionId, this.transport,
                                                                                 errorRef);

    this.connection.setMessageSink(tester);

    transport.open();

    assertTrue(errorRef.get() == null);

    List sentMessages = connection.getSentMessages();

    assertEquals(2, sentMessages.size());
    assertEquals(this.connectionId, transport.getConnectionId());
    Thread.sleep(1000);
    assertTrue(tester.waitForAckToBeReceived(3000));
  }

  /**
   * Test interaction with a real network listener.
   */
  public void testConnectAndHandshakeActuallyConnected() throws Exception {
    CommunicationsManager commsMgr = new CommunicationsManagerImpl(new NullMessageMonitor(),
                                                                   new PlainNetworkStackHarnessFactory(),
                                                                   new NullConnectionPolicy(), 0);
    NetworkListener listener = commsMgr.createListener(new NullSessionManager(), new TCSocketAddress(0), true,
                                                       new DefaultConnectionIdFactory());
    listener.start(Collections.EMPTY_SET);
    int port = listener.getBindPort();

    final ConnectionInfo connInfo = new ConnectionInfo(TCSocketAddress.LOOPBACK_IP, port);
    ClientConnectionEstablisher cce = new ClientConnectionEstablisher(
                                                                      commsMgr.getConnectionManager(),
                                                                      new ConnectionAddressProvider(
                                                                                                    new ConnectionInfo[] { connInfo }),
                                                                      0, 1000);
    transport = new ClientMessageTransport(cce, this.handshakeErrorHandler, this.transportMessageFactory,
                                           new WireProtocolAdaptorFactoryImpl());
    transport.open();
    assertTrue(transport.isConnected());
    listener.stop(5000);

  }
}
