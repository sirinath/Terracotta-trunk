/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.net.core;

import EDU.oswego.cs.dl.util.concurrent.BoundedLinkedQueue;

import com.tc.async.api.Stage;
import com.tc.async.impl.StageManagerImpl;
import com.tc.exception.ImplementMe;
import com.tc.lang.TCThreadGroup;
import com.tc.lang.ThrowableHandler;
import com.tc.logging.LogLevelImpl;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.ServerID;
import com.tc.net.TCSocketAddress;
import com.tc.net.protocol.NetworkStackHarnessFactory;
import com.tc.net.protocol.PlainNetworkStackHarnessFactory;
import com.tc.net.protocol.delivery.OOOEventHandler;
import com.tc.net.protocol.delivery.OOONetworkStackHarnessFactory;
import com.tc.net.protocol.delivery.OnceAndOnlyOnceProtocolNetworkLayerFactoryImpl;
import com.tc.net.protocol.tcm.ClientMessageChannel;
import com.tc.net.protocol.tcm.CommunicationsManager;
import com.tc.net.protocol.tcm.CommunicationsManagerImpl;
import com.tc.net.protocol.tcm.NetworkListener;
import com.tc.net.protocol.tcm.NullMessageMonitor;
import com.tc.net.protocol.transport.ClientConnectionEstablisher;
import com.tc.net.protocol.transport.ClientMessageTransport;
import com.tc.net.protocol.transport.DefaultConnectionIdFactory;
import com.tc.net.protocol.transport.HealthCheckerConfigImpl;
import com.tc.net.protocol.transport.NullConnectionPolicy;
import com.tc.net.protocol.transport.TransportHandshakeErrorContext;
import com.tc.net.protocol.transport.TransportHandshakeErrorHandler;
import com.tc.net.protocol.transport.TransportHandshakeMessage;
import com.tc.net.protocol.transport.TransportMessageFactoryImpl;
import com.tc.net.protocol.transport.TransportNetworkStackHarnessFactory;
import com.tc.net.protocol.transport.WireProtocolAdaptorFactoryImpl;
import com.tc.net.proxy.TCPProxy;
import com.tc.object.session.NullSessionManager;
import com.tc.properties.L1ReconnectConfigImpl;
import com.tc.properties.TCPropertiesImpl;
import com.tc.test.TCTestCase;
import com.tc.util.Assert;
import com.tc.util.PortChooser;
import com.tc.util.concurrent.QueueFactory;
import com.tc.util.concurrent.ThreadUtil;

import java.net.InetAddress;
import java.util.Collections;

public class TCWorkerCommManagerTest extends TCTestCase {
  TCLogger    logger               = TCLogging.getLogger(TCWorkerCommManager.class);
  private int L1_RECONNECT_TIMEOUT = 15000;

  private ClientMessageTransport createClient(CommunicationsManager commsMgr, int port) {
    final ConnectionInfo connInfo = new ConnectionInfo(TCSocketAddress.LOOPBACK_IP, port);
    ClientConnectionEstablisher cce = new ClientConnectionEstablisher(
                                                                      commsMgr.getConnectionManager(),
                                                                      new ConnectionAddressProvider(
                                                                                                    new ConnectionInfo[] { connInfo }),
                                                                      0, 1000);

    return new ClientMessageTransport(cce, createHandshakeErrorHandler(), new TransportMessageFactoryImpl(),
                                      new WireProtocolAdaptorFactoryImpl(), TransportHandshakeMessage.NO_CALLBACK_PORT);
  }

  private NetworkStackHarnessFactory getNetworkStackHarnessFactory(boolean enableReconnect) {
    NetworkStackHarnessFactory networkStackHarnessFactory;
    if (enableReconnect) {
      StageManagerImpl stageManager = new StageManagerImpl(new TCThreadGroup(new ThrowableHandler(TCLogging
          .getLogger(StageManagerImpl.class))), new QueueFactory(BoundedLinkedQueue.class.getName()));
      final Stage oooSendStage = stageManager.createStage("OOONetSendStage", new OOOEventHandler(), 1, 5000);
      final Stage oooReceiveStage = stageManager.createStage("OOONetReceiveStage", new OOOEventHandler(), 1, 5000);
      networkStackHarnessFactory = new OOONetworkStackHarnessFactory(
                                                                     new OnceAndOnlyOnceProtocolNetworkLayerFactoryImpl(),
                                                                     oooSendStage.getSink(), oooReceiveStage.getSink(),
                                                                     new L1ReconnectConfigImpl(true,
                                                                                               L1_RECONNECT_TIMEOUT,
                                                                                               5000, 16, 32));
    } else {
      networkStackHarnessFactory = new PlainNetworkStackHarnessFactory();
    }
    return networkStackHarnessFactory;
  }

  @Override
  protected void setUp() throws Exception {
    logger.setLevel(LogLevelImpl.DEBUG);
    super.setUp();
  }

  public void testBasic() throws Exception {
    // comms manager with 4 worker comms
    CommunicationsManager commsMgr = new CommunicationsManagerImpl(new NullMessageMonitor(),
                                                                   new TransportNetworkStackHarnessFactory(),
                                                                   new NullConnectionPolicy(), 4);
    NetworkListener listener = commsMgr.createListener(new NullSessionManager(), new TCSocketAddress(0), true,
                                                       new DefaultConnectionIdFactory());
    listener.start(Collections.EMPTY_SET);
    int port = listener.getBindPort();

    ClientMessageTransport client1 = createClient(commsMgr, port);
    ClientMessageTransport client2 = createClient(commsMgr, port);
    ClientMessageTransport client3 = createClient(commsMgr, port);
    ClientMessageTransport client4 = createClient(commsMgr, port);

    client1.open();
    client2.open();
    client3.open();
    client4.open();

    ThreadUtil.reallySleep(5000);
    assertTrue(client1.isConnected());
    assertTrue(client2.isConnected());
    assertTrue(client3.isConnected());
    assertTrue(client4.isConnected());

    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(3));

    listener.stop(5000);
  }

  public void testWorkerCommDistributionAfterClose() throws Exception {
    // comms manager with 3 worker comms
    CommunicationsManager commsMgr = new CommunicationsManagerImpl(new NullMessageMonitor(),
                                                                   getNetworkStackHarnessFactory(false),
                                                                   new NullConnectionPolicy(), 3);
    NetworkListener listener = commsMgr.createListener(new NullSessionManager(), new TCSocketAddress(0), true,
                                                       new DefaultConnectionIdFactory());
    listener.start(Collections.EMPTY_SET);
    int port = listener.getBindPort();

    ClientMessageChannel client1 = createClientMsgCh(port, false);
    ClientMessageChannel client2 = createClientMsgCh(port, false);
    ClientMessageChannel client3 = createClientMsgCh(port, false);

    client1.open();
    client2.open();
    client3.open();

    ThreadUtil.reallySleep(2000);
    assertTrue(client1.isConnected());
    assertTrue(client2.isConnected());
    assertTrue(client3.isConnected());

    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2));

    // case 1 :
    // two client closes their connections

    client1.close();
    client2.close();

    ThreadUtil.reallySleep(5000);

    ClientMessageChannel client4 = createClientMsgCh(port, false);
    ClientMessageChannel client5 = createClientMsgCh(port, false);

    // two clients open new connection
    client4.open();
    client5.open();

    ThreadUtil.reallySleep(5000);

    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2));

    commsMgr.getConnectionManager().closeAllConnections(1000);
    ThreadUtil.reallySleep(5000);

    Assert.assertEquals(0, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0));
    Assert.assertEquals(0, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1));
    Assert.assertEquals(0, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2));

    listener.stop(5000);
  }

  private ClientMessageChannel createClientMsgCh(int port) {
    return createClientMsgCh(port, true);
  }

  private ClientMessageChannel createClientMsgCh(int port, boolean ooo) {

    CommunicationsManager clientComms = new CommunicationsManagerImpl(new NullMessageMonitor(),
                                                                      getNetworkStackHarnessFactory(ooo),
                                                                      new NullConnectionPolicy());

    ClientMessageChannel clientMsgCh = clientComms
        .createClientChannel(
                             new NullSessionManager(),
                             -1,
                             "localhost",
                             port,
                             1000,
                             new ConnectionAddressProvider(
                                                           new ConnectionInfo[] { new ConnectionInfo("localhost", port) }));
    return clientMsgCh;
  }

  public void testWorkerCommDistributionAfterReconnect() throws Exception {
    // comms manager with 3 worker comms
    CommunicationsManager commsMgr = new CommunicationsManagerImpl(new NullMessageMonitor(),
                                                                   getNetworkStackHarnessFactory(true),
                                                                   new NullConnectionPolicy(), 3,
                                                                   new HealthCheckerConfigImpl(TCPropertiesImpl
                                                                       .getProperties()
                                                                       .getPropertiesFor("l2.healthcheck.l1"),
                                                                                               "Test Server"),
                                                                   new ServerID());
    NetworkListener listener = commsMgr.createListener(new NullSessionManager(), new TCSocketAddress(0), true,
                                                       new DefaultConnectionIdFactory());
    listener.start(Collections.EMPTY_SET);
    int serverPort = listener.getBindPort();

    int proxyPort = new PortChooser().chooseRandomPort();
    TCPProxy proxy = new TCPProxy(proxyPort, InetAddress.getByName("localhost"), serverPort, 0, false, null);
    proxy.start();

    ClientMessageChannel client1 = createClientMsgCh(proxyPort);
    ClientMessageChannel client2 = createClientMsgCh(proxyPort);
    ClientMessageChannel client3 = createClientMsgCh(proxyPort);

    client1.open();
    client2.open();
    client3.open();

    ThreadUtil.reallySleep(2000);
    assertTrue(client1.isConnected());
    assertTrue(client2.isConnected());
    assertTrue(client3.isConnected());

    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2));

    // case 1 : network problems .. both ends getting events
    proxy.stop();

    ThreadUtil.reallySleep(5000);
    Assert.assertEquals(0, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0));
    Assert.assertEquals(0, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1));
    Assert.assertEquals(0, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2));

    proxy.start();

    ThreadUtil.reallySleep(5000);
    assertTrue(client1.isConnected());
    assertTrue(client2.isConnected());
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1));
    Assert.assertEquals(1, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2));

    // case 2: problem with the client side connections .. but server still thinks clients are connected
    proxy.closeClientConnections(true, false);

    ThreadUtil.reallySleep(5000);

    System.out.println("XXX waiting for clients to reconnect");
    while ((((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0) != 1)
           && (((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1) != 1)
           && (((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2) != 1)) {
      System.out.print(".");
      ThreadUtil.reallySleep(5000);
    }

    // case 3: connecting three more clients through server ports
    ClientMessageChannel client4 = createClientMsgCh(serverPort);
    ClientMessageChannel client5 = createClientMsgCh(serverPort);
    ClientMessageChannel client6 = createClientMsgCh(serverPort);

    client4.open();
    client5.open();
    client6.open();

    ThreadUtil.reallySleep(2000);
    assertTrue(client4.isConnected());
    assertTrue(client5.isConnected());
    assertTrue(client6.isConnected());

    Assert.assertEquals(2, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0));
    Assert.assertEquals(2, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1));
    Assert.assertEquals(2, ((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2));

    // case 4: server detecting long gcs and kicking out the clients
    proxy.setDelay(15 * 1000);

    System.out.println("XXX waiting for HC to kick out the clients those who connected thru proxy ports");
    while ((((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0) != 1)
           && (((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1) != 1)
           && (((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2) != 1)) {
      System.out.print(".");
      ThreadUtil.reallySleep(5000);
    }

    proxy.setDelay(0);

    System.out.println("XXX waiting for clients reconnect");
    while ((((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0) != 2)
           && (((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1) != 2)
           && (((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2) != 2)) {
      System.out.print(".");
      ThreadUtil.reallySleep(5000);
    }

    // case 5: closing all connections from server side
    System.out.println("XXX closing all client connections");
    commsMgr.getConnectionManager().closeAllConnections(1000);

    // all clients should reconnect and should be distributed fairly among the worker comms.
    ThreadUtil.reallySleep(5000);
    
    System.out.println("XXX waiting for all clients reconnect");
    while ((((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(0) != 2)
           && (((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(1) != 2)
           && (((TCCommJDK14) commsMgr.getConnectionManager().getTcComm()).getClientCountForWorkerComm(2) != 2)) {
      System.out.print(".");
      ThreadUtil.reallySleep(5000);
    }

    listener.stop(5000);
  }

  private TransportHandshakeErrorHandler createHandshakeErrorHandler() {
    return new TransportHandshakeErrorHandler() {

      public void handleHandshakeError(TransportHandshakeErrorContext e) {
        new ImplementMe(e.toString()).printStackTrace();
      }

      public void handleHandshakeError(TransportHandshakeErrorContext e, TransportHandshakeMessage m) {
        throw new ImplementMe();

      }

    };
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
  }
}
