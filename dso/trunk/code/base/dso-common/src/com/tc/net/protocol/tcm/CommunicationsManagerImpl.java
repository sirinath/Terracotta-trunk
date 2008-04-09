/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.tcm;

import com.tc.async.api.Sink;
import com.tc.async.impl.NullSink;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.TCSocketAddress;
import com.tc.net.core.ConnectionAddressProvider;
import com.tc.net.core.Constants;
import com.tc.net.core.TCConnection;
import com.tc.net.core.TCConnectionManager;
import com.tc.net.core.TCConnectionManagerJDK14;
import com.tc.net.core.TCListener;
import com.tc.net.protocol.NetworkStackHarness;
import com.tc.net.protocol.NetworkStackHarnessFactory;
import com.tc.net.protocol.transport.ClientConnectionEstablisher;
import com.tc.net.protocol.transport.ClientMessageTransport;
import com.tc.net.protocol.transport.ConnectionHealthChecker;
import com.tc.net.protocol.transport.ConnectionHealthCheckerImpl;
import com.tc.net.protocol.transport.ConnectionID;
import com.tc.net.protocol.transport.ConnectionIDFactory;
import com.tc.net.protocol.transport.ConnectionPolicy;
import com.tc.net.protocol.transport.DisabledHealthCheckerConfigImpl;
import com.tc.net.protocol.transport.HealthCheckerConfig;
import com.tc.net.protocol.transport.MessageTransport;
import com.tc.net.protocol.transport.MessageTransportFactory;
import com.tc.net.protocol.transport.MessageTransportListener;
import com.tc.net.protocol.transport.ConnectionHealthCheckerEchoImpl;
import com.tc.net.protocol.transport.ServerMessageTransport;
import com.tc.net.protocol.transport.ServerStackProvider;
import com.tc.net.protocol.transport.TransportHandshakeError;
import com.tc.net.protocol.transport.TransportHandshakeErrorContext;
import com.tc.net.protocol.transport.TransportHandshakeErrorHandler;
import com.tc.net.protocol.transport.TransportHandshakeMessage;
import com.tc.net.protocol.transport.TransportHandshakeMessageFactory;
import com.tc.net.protocol.transport.TransportMessageFactoryImpl;
import com.tc.net.protocol.transport.WireProtocolAdaptorFactoryImpl;
import com.tc.net.protocol.transport.WireProtocolMessageSink;
import com.tc.object.session.SessionProvider;
import com.tc.util.Assert;
import com.tc.util.concurrent.SetOnceFlag;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Communications manager for setting up listners and creating client connections
 *
 * @author teck
 */
public class CommunicationsManagerImpl implements CommunicationsManager {
  private static final TCLogger                  logger    = TCLogging.getLogger(CommunicationsManager.class);

  private final SetOnceFlag                      shutdown  = new SetOnceFlag();
  private final Set                              listeners = new HashSet();
  private final TCConnectionManager              connectionManager;
  private final boolean                          privateConnMgr;
  private final NetworkStackHarnessFactory       stackHarnessFactory;
  private final TransportHandshakeMessageFactory transportMessageFactory;
  private final MessageMonitor                   monitor;
  private final ConnectionPolicy                 connectionPolicy;
  private ConnectionHealthChecker                connectionHealthChecker;

  /**
   * Create a communications manager. This implies that one or more network handling threads will be started on your
   * behalf. As such, you should not be instantiating one of these per connection for instance.
   */
  public CommunicationsManagerImpl(MessageMonitor monitor, NetworkStackHarnessFactory stackHarnessFactory,
                                   ConnectionPolicy connectionPolicy) {
    this(monitor, stackHarnessFactory, null, connectionPolicy, 0, new DisabledHealthCheckerConfigImpl());
  }

  public CommunicationsManagerImpl(MessageMonitor monitor, NetworkStackHarnessFactory stackHarnessFactory,
                                   ConnectionPolicy connectionPolicy, int workerCommCount) {
    this(monitor, stackHarnessFactory, null, connectionPolicy, workerCommCount, new DisabledHealthCheckerConfigImpl());
  }

  public CommunicationsManagerImpl(MessageMonitor monitor, NetworkStackHarnessFactory stackHarnessFactory,
                                   TCConnectionManager connMgr, ConnectionPolicy connectionPolicy) {
    this(monitor, stackHarnessFactory, connMgr, connectionPolicy, 0, new DisabledHealthCheckerConfigImpl());
  }

  public CommunicationsManagerImpl(MessageMonitor monitor, NetworkStackHarnessFactory stackHarnessFactory,
                                   ConnectionPolicy connectionPolicy, HealthCheckerConfig config) {
    this(monitor, stackHarnessFactory, null, connectionPolicy, 0, config);
  }

  public CommunicationsManagerImpl(MessageMonitor monitor, NetworkStackHarnessFactory stackHarnessFactory,
                                   ConnectionPolicy connectionPolicy, int workerCommCount, HealthCheckerConfig config) {
    this(monitor, stackHarnessFactory, null, connectionPolicy, workerCommCount, config);
  }

  /**
   * Create a comms manager with the given connection manager. This cstr is mostly for testing, or in the event that you
   * actually want to use an explicit connection manager
   *
   * @param connMgr the connection manager to use
   * @param serverDescriptors
   */
  public CommunicationsManagerImpl(MessageMonitor monitor, NetworkStackHarnessFactory stackHarnessFactory,
                                   TCConnectionManager connMgr, ConnectionPolicy connectionPolicy, int workerCommCount,
                                   HealthCheckerConfig healthCheckerConfig) {

    this.monitor = monitor;
    this.transportMessageFactory = new TransportMessageFactoryImpl();
    this.connectionPolicy = connectionPolicy;
    this.stackHarnessFactory = stackHarnessFactory;
    privateConnMgr = (connMgr == null);

    if (null == connMgr) {
      this.connectionManager = new TCConnectionManagerJDK14(workerCommCount);
    } else {
      this.connectionManager = connMgr;
    }

    Assert.eval(healthCheckerConfig != null);
    if (healthCheckerConfig.isHealthCheckerEnabled()) {
      connectionHealthChecker = new ConnectionHealthCheckerImpl(healthCheckerConfig, connectionManager);
    } else {
      connectionHealthChecker = new ConnectionHealthCheckerEchoImpl();
    }
    connectionHealthChecker.start();
  }

  public TCConnectionManager getConnectionManager() {
    return this.connectionManager;
  }

  public boolean isInShutdown() {
    return shutdown.isSet();
  }

  public void shutdown() {
    if (shutdown.attemptSet()) {
      connectionHealthChecker.stop();
      if (privateConnMgr) {
        connectionManager.shutdown();
      }
    } else {
      logger.warn("shutdown already started");
    }
  }

  public NetworkListener[] getAllListeners() {
    synchronized (listeners) {
      return (NetworkListener[]) listeners.toArray(new NetworkListener[listeners.size()]);
    }
  }

  public ClientMessageChannel createClientChannel(final SessionProvider sessionProvider, final int maxReconnectTries,
                                                  String hostname, int port, final int timeout,
                                                  ConnectionAddressProvider addressProvider) {
    return createClientChannel(sessionProvider, maxReconnectTries, hostname, port, timeout, addressProvider,
                               TransportHandshakeMessage.NO_CALLBACK_PORT);
  }

  public ClientMessageChannel createClientChannel(final SessionProvider sessionProvider, final int maxReconnectTries,
                                                  String hostname, int port, final int timeout,
                                                  ConnectionAddressProvider addressProvider, final int callbackPort) {
    final ConnectionAddressProvider provider = addressProvider;
    MessageTransportFactory transportFactory = new MessageTransportFactory() {
      public MessageTransport createNewTransport() {
        TransportHandshakeErrorHandler handshakeErrorHandler = new TransportHandshakeErrorHandler() {

          public void handleHandshakeError(TransportHandshakeErrorContext e) {
            if (e.getErrorType() == TransportHandshakeError.ERROR_STACK_MISMATCH) System.err.println(e.getMessage());
            else System.err.println(e);
            new TCRuntimeException("I'm crashing the client!").printStackTrace();
            try {
              Thread.sleep(30 * 1000);
            } catch (InterruptedException e1) {
              e1.printStackTrace();
            }
            System.exit(1);
          }

          public void handleHandshakeError(TransportHandshakeErrorContext e, TransportHandshakeMessage m) {
            System.err.println(e);
            System.err.println(m);
            new TCRuntimeException("I'm crashing the client").printStackTrace();
            try {
              Thread.sleep(30 * 1000);
            } catch (InterruptedException e1) {
              e1.printStackTrace();
            }
            System.exit(1);
          }

        };

        ClientConnectionEstablisher clientConnectionEstablisher = new ClientConnectionEstablisher(connectionManager,
                                                                                                  provider,
                                                                                                  maxReconnectTries,
                                                                                                  timeout);
        ClientMessageTransport cmt = new ClientMessageTransport(clientConnectionEstablisher, handshakeErrorHandler,
                                                                transportMessageFactory,
                                                                new WireProtocolAdaptorFactoryImpl(), callbackPort);
        cmt.addTransportListener(connectionHealthChecker);
        return cmt;
      }

      public MessageTransport createNewTransport(ConnectionID connectionID, TransportHandshakeErrorHandler handler,
                                                 TransportHandshakeMessageFactory handshakeMessageFactory,
                                                 List transportListeners) {
        throw new AssertionError();
      }

      public MessageTransport createNewTransport(ConnectionID connectionId, TCConnection connection,
                                                 TransportHandshakeErrorHandler handler,
                                                 TransportHandshakeMessageFactory handshakeMessageFactory,
                                                 List transportListeners) {
        throw new AssertionError();
      }
    };

    return createClientChannel(sessionProvider, maxReconnectTries, hostname, port, timeout, addressProvider,
                               transportFactory);
  }

  public ClientMessageChannel createClientChannel(SessionProvider sessionProvider, final int maxReconnectTries,
                                                  String hostname, int port, final int timeout,
                                                  ConnectionAddressProvider addressProvider,
                                                  MessageTransportFactory transportFactory) {
    // XXX: maxReconnectTries MUST be non-zero if we have a
    // once and only once protocol stack.
    ClientMessageChannelImpl rv = new ClientMessageChannelImpl(new TCMessageFactoryImpl(sessionProvider, monitor),
                                                               new TCMessageRouterImpl(), sessionProvider);

    NetworkStackHarness stackHarness = this.stackHarnessFactory.createClientHarness(transportFactory, rv,
                                                                                    new MessageTransportListener[0]);

    stackHarness.finalizeStack();

    return rv;
  }

  /**
   * Creates a network listener with a default network stack.
   */
  public NetworkListener createListener(SessionProvider sessionProvider, TCSocketAddress addr,
                                        boolean transportDisconnectRemovesChannel,
                                        ConnectionIDFactory connectionIdFactory) {
    return createListener(sessionProvider, addr, transportDisconnectRemovesChannel, connectionIdFactory, true);
  }

  public NetworkListener createListener(SessionProvider sessionProvider, TCSocketAddress address,
                                        boolean transportDisconnectRemovesChannel,
                                        ConnectionIDFactory connectionIDFactory, Sink httpSink) {
    return createListener(sessionProvider, address, transportDisconnectRemovesChannel, connectionIDFactory, true,
                          httpSink, null);
  }

  public NetworkListener createListener(SessionProvider sessionProvider, TCSocketAddress addr,
                                        boolean transportDisconnectRemovesChannel,
                                        ConnectionIDFactory connectionIdFactory, boolean reuseAddr) {
    return createListener(sessionProvider, addr, transportDisconnectRemovesChannel, connectionIdFactory, reuseAddr,
                          new NullSink(), null);
  }

  public NetworkListener createListener(SessionProvider sessionProvider, TCSocketAddress addr,
                                        boolean transportDisconnectRemovesChannel,
                                        ConnectionIDFactory connectionIdFactory, WireProtocolMessageSink wireProtoMsgSnk) {
    return createListener(sessionProvider, addr, transportDisconnectRemovesChannel, connectionIdFactory, true,
                          new NullSink(), wireProtoMsgSnk);
  }

  /**
   * Creates a network listener with a default network stack.
   */
  private NetworkListener createListener(SessionProvider sessionProvider, TCSocketAddress addr,
                                         boolean transportDisconnectRemovesChannel,
                                         ConnectionIDFactory connectionIdFactory, boolean reuseAddr, Sink httpSink,
                                         WireProtocolMessageSink wireProtoMsgSnk) {
    if (shutdown.isSet()) { throw new IllegalStateException("Comms manger shut down"); }

    // The idea here is that someday we might want to pass in a custom channel factory. The reason you might want to do
    // that is so thay you can control the actual class of the channels created off this listener
    final TCMessageRouter msgRouter = new TCMessageRouterImpl();
    final TCMessageFactory msgFactory = new TCMessageFactoryImpl(sessionProvider, monitor);
    final ServerMessageChannelFactory channelFactory = new ServerMessageChannelFactory() {
      public MessageChannelInternal createNewChannel(ChannelID id) {
        return new ServerMessageChannelImpl(id, msgRouter, msgFactory);
      }
    };

    final ChannelManagerImpl channelManager = new ChannelManagerImpl(transportDisconnectRemovesChannel, channelFactory);

    return new NetworkListenerImpl(addr, this, channelManager, msgFactory, msgRouter, reuseAddr, connectionIdFactory,
                                   httpSink, wireProtoMsgSnk);
  }

  TCListener createCommsListener(TCSocketAddress addr, final ServerMessageChannelFactory channelFactory,
                                 boolean resueAddr, Set initialConnectionIDs, ConnectionIDFactory connectionIdFactory,
                                 Sink httpSink, WireProtocolMessageSink wireProtocolMessageSink) throws IOException {

    MessageTransportFactory transportFactory = new MessageTransportFactory() {
      public MessageTransport createNewTransport() {
        throw new AssertionError();
      }

      public MessageTransport createNewTransport(ConnectionID connectionID, TransportHandshakeErrorHandler handler,
                                                 TransportHandshakeMessageFactory handshakeMessageFactory,
                                                 List transportListeners) {
        MessageTransport rv = new ServerMessageTransport(connectionID, handler, handshakeMessageFactory);
        rv.addTransportListeners(transportListeners);
        rv.addTransportListener(connectionHealthChecker);
        return rv;
      }

      public MessageTransport createNewTransport(ConnectionID connectionId, TCConnection connection,
                                                 TransportHandshakeErrorHandler handler,
                                                 TransportHandshakeMessageFactory handshakeMessageFactory,
                                                 List transportListeners) {
        MessageTransport rv = new ServerMessageTransport(connectionId, connection, handler, handshakeMessageFactory);
        rv.addTransportListeners(transportListeners);
        rv.addTransportListener(connectionHealthChecker);
        return rv;
      }
    };

    ServerStackProvider stackProvider = new ServerStackProvider(TCLogging.getLogger(ServerStackProvider.class),
                                                                initialConnectionIDs, stackHarnessFactory,
                                                                channelFactory, transportFactory,
                                                                this.transportMessageFactory, connectionIdFactory,
                                                                this.connectionPolicy,
                                                                new WireProtocolAdaptorFactoryImpl(httpSink),
                                                                wireProtocolMessageSink);
    return connectionManager.createListener(addr, stackProvider, Constants.DEFAULT_ACCEPT_QUEUE_DEPTH, resueAddr);
  }

  void registerListener(NetworkListener lsnr) {
    synchronized (listeners) {
      boolean added = listeners.add(lsnr);

      if (!added) {
        logger.warn("replaced an existing listener in the listener map");
      }
    }
  }

  void unregisterListener(NetworkListener lsnr) {
    synchronized (listeners) {
      listeners.remove(lsnr);
    }
  }

  /* Following routines are strictly for testing only */
  public ConnectionHealthChecker getConnHealthChecker() {
    return this.connectionHealthChecker;
  }

  public void setConnHealthChecker(ConnectionHealthChecker checker) {
    this.connectionHealthChecker.stop();
    this.connectionHealthChecker = checker;
    this.connectionHealthChecker.start();
  }

}
