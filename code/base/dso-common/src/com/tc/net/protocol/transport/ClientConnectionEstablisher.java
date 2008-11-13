/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import EDU.oswego.cs.dl.util.concurrent.SynchronizedBoolean;

import com.tc.logging.LossyTCLogger;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.MaxConnectionsExceededException;
import com.tc.net.TCSocketAddress;
import com.tc.net.core.ConnectionAddressIterator;
import com.tc.net.core.ConnectionAddressProvider;
import com.tc.net.core.ConnectionInfo;
import com.tc.net.core.TCConnection;
import com.tc.net.core.TCConnectionManager;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;
import com.tc.util.TCTimeoutException;
import com.tc.util.concurrent.NoExceptionLinkedQueue;

import java.io.IOException;

/**
 * This guy establishes a connection to the server for the Client.
 */
public class ClientConnectionEstablisher {

  private static final long               CONNECT_RETRY_INTERVAL;

  private static final long               MIN_RETRY_INTERVAL = 10;

  static {
    TCLogger logger = TCLogging.getLogger(ClientConnectionEstablisher.class);

    long value = TCPropertiesImpl.getProperties().getLong(TCPropertiesConsts.L1_SOCKET_RECONNECT_WAIT_INTERVAL);
    if (value < MIN_RETRY_INTERVAL) {
      logger.warn("Forcing reconnect wait interval to " + MIN_RETRY_INTERVAL + " (configured value was " + value + ")");
      value = MIN_RETRY_INTERVAL;
    }

    CONNECT_RETRY_INTERVAL = value;
  }

  private final String                    desc;
  private final int                       maxReconnectTries;
  private final int                       timeout;
  private final ConnectionAddressProvider connAddressProvider;
  private final TCConnectionManager       connManager;

  private final SynchronizedBoolean       asyncReconnecting  = new SynchronizedBoolean(false);

  private Thread                          connectionEstablisher;

  private NoExceptionLinkedQueue          reconnectRequest   = new NoExceptionLinkedQueue();  // <ConnectionRequest>

  public ClientConnectionEstablisher(TCConnectionManager connManager, ConnectionAddressProvider connAddressProvider,
                                     int maxReconnectTries, int timeout) {
    this.connManager = connManager;
    this.connAddressProvider = connAddressProvider;
    this.maxReconnectTries = maxReconnectTries;
    this.timeout = timeout;

    if (maxReconnectTries == 0) desc = "none";
    else if (maxReconnectTries < 0) desc = "unlimited";
    else desc = "" + maxReconnectTries;

  }

  /**
   * Blocking open. Causes a connection to be made. Will throw exceptions if the connect fails.
   * 
   * @throws TCTimeoutException
   * @throws IOException
   * @throws TCTimeoutException
   * @throws MaxConnectionsExceededException
   */
  public TCConnection open(ClientMessageTransport cmt) throws TCTimeoutException, IOException {
    synchronized (asyncReconnecting) {
      Assert.eval("Can't call open() while asynch reconnect occurring", !asyncReconnecting.get());
      return connectTryAllOnce(cmt);
    }
  }

  private TCConnection connectTryAllOnce(ClientMessageTransport cmt) throws TCTimeoutException, IOException {
    final ConnectionAddressIterator addresses = connAddressProvider.getIterator();
    TCConnection rv = null;
    while (addresses.hasNext()) {
      final ConnectionInfo connInfo = addresses.next();
      try {
        final TCSocketAddress csa = new TCSocketAddress(connInfo);
        rv = connect(csa, cmt);
        break;
      } catch (TCTimeoutException e) {
        if (!addresses.hasNext()) { throw e; }
      } catch (IOException e) {
        if (!addresses.hasNext()) { throw e; }
      }
    }
    return rv;
  }

  /**
   * Tries to make a connection. This is a blocking call.
   * 
   * @return
   * @throws TCTimeoutException
   * @throws IOException
   * @throws MaxConnectionsExceededException
   */
  TCConnection connect(TCSocketAddress sa, ClientMessageTransport cmt) throws TCTimeoutException, IOException {

    TCConnection connection = this.connManager.createConnection(cmt.getProtocolAdapter());
    cmt.fireTransportConnectAttemptEvent();
    try {
      connection.connect(sa, timeout);
    } catch (IOException e) {
      connection.close(100);
      throw e;
    } catch (TCTimeoutException e) {
      connection.close(100);
      throw e;
    }
    return connection;
  }

  public String toString() {
    return "ClientConnectionEstablisher[" + connAddressProvider + ", timeout=" + timeout + "]";
  }

  private void reconnect(ClientMessageTransport cmt) throws MaxConnectionsExceededException {
    try {
      // Lossy logging for connection errors. Log the errors once in every 10 seconds
      LossyTCLogger connectionErrorLossyLogger = new LossyTCLogger(cmt.logger, 10000, LossyTCLogger.TIME_BASED, true);

      boolean connected = cmt.isConnected();
      if (connected) {
        cmt.logger.warn("Got reconnect request for ClientMessageTransport that is connected.  skipping");
        return;
      }

      asyncReconnecting.set(true);
      for (int i = 0; ((maxReconnectTries < 0) || (i < maxReconnectTries)) && !connected; i++) {
        ConnectionAddressIterator addresses = connAddressProvider.getIterator();
        while (addresses.hasNext() && !connected) {

          TCConnection connection = null;
          final ConnectionInfo connInfo = addresses.next();

          // DEV-1945
          if (i == 0) {
            String previousConnectHostName = cmt.getRemoteAddress().getAddress().getHostName();
            String connectingToHostName = connInfo.getHostname();

            int previousConnectHostPort = cmt.getRemoteAddress().getPort();
            int connectingToHostPort = connInfo.getPort();

            if ((addresses.hasNext()) && (previousConnectHostName.equals(connectingToHostName))
                && (previousConnectHostPort == connectingToHostPort)) {
              continue;
            }
          }

          try {
            if (i % 20 == 0) {
              cmt.logger.warn("Reconnect attempt " + i + " of " + desc + " reconnect tries to " + connInfo
                              + ", timeout=" + timeout);
            }
            connection = connect(new TCSocketAddress(connInfo), cmt);
            cmt.reconnect(connection);
            connected = true;
          } catch (MaxConnectionsExceededException e) {
            throw e;
          } catch (TCTimeoutException e) {
            handleConnectException(e, false, connectionErrorLossyLogger, connection);
          } catch (IOException e) {
            handleConnectException(e, false, connectionErrorLossyLogger, connection);
          } catch (Exception e) {
            handleConnectException(e, true, connectionErrorLossyLogger, connection);
          }

        }
      }
      cmt.endIfDisconnected();
    } finally {
      asyncReconnecting.set(false);
    }
  }

  private void restoreConnection(ClientMessageTransport cmt, TCSocketAddress sa, long timeoutMillis,
                                 RestoreConnectionCallback callback) {
    final long deadline = System.currentTimeMillis() + timeoutMillis;
    boolean connected = cmt.isConnected();
    if (connected) {
      cmt.logger.warn("Got restoreConnection request for ClientMessageTransport that is connected.  skipping");
    }

    asyncReconnecting.set(true);
    for (int i = 0; !connected; i++) {
      TCConnection connection = null;
      try {
        connection = connect(sa, cmt);
        cmt.reconnect(connection);
        connected = true;
      } catch (MaxConnectionsExceededException e) {
        // nothing
      } catch (TCTimeoutException e) {
        handleConnectException(e, false, cmt.logger, connection);
      } catch (IOException e) {
        handleConnectException(e, false, cmt.logger, connection);
      } catch (Exception e) {
        handleConnectException(e, true, cmt.logger, connection);
      }
      if (connected || System.currentTimeMillis() > deadline) {
        break;
      }
    }
    asyncReconnecting.set(false);
    if (!connected) {
      callback.restoreConnectionFailed(cmt);
    }
  }

  private void handleConnectException(Exception e, boolean logFullException, TCLogger logger, TCConnection connection) {
    if (connection != null) connection.close(100);

    if (logger.isDebugEnabled() || logFullException) {
      logger.error("Connect Exception", e);
    } else {
      logger.warn(e.getMessage());
    }

    if (CONNECT_RETRY_INTERVAL > 0) {
      try {
        Thread.sleep(CONNECT_RETRY_INTERVAL);
      } catch (InterruptedException e1) {
        //
      }
    }
  }

  public void asyncReconnect(ClientMessageTransport cmt) {
    synchronized (asyncReconnecting) {
      if (asyncReconnecting.get()) return;
      putReconnectRequest(new ConnectionRequest(ConnectionRequest.RECONNECT, cmt));
    }
  }

  public void asyncRestoreConnection(ClientMessageTransport cmt, TCSocketAddress sa,
                                     RestoreConnectionCallback callback, long timeoutMillis) {
    synchronized (asyncReconnecting) {
      if (asyncReconnecting.get()) return;
      putReconnectRequest(new RestoreConnectionRequest(cmt, sa, callback, timeoutMillis));
    }
  }

  private void putReconnectRequest(ConnectionRequest request) {
    if (connectionEstablisher == null) {
      // First time
      // Allow the async thread reconnects/restores only when cmt was connected atleast once
      if ((request.getClientMessageTransport() == null) || (!request.getClientMessageTransport().wasOpened())) return;

      connectionEstablisher = new Thread(new AsyncReconnect(this), "ConnectionEstablisher");
      connectionEstablisher.setDaemon(true);
      connectionEstablisher.start();

    }

    // DEV-1140 : avoiding the race condition
    // asyncReconnecting.set(true);
    reconnectRequest.put(request);
  }

  public void quitReconnectAttempts() {
    putReconnectRequest(new ConnectionRequest(ConnectionRequest.QUIT, null));
  }

  static class AsyncReconnect implements Runnable {
    private final ClientConnectionEstablisher cce;

    public AsyncReconnect(ClientConnectionEstablisher cce) {
      this.cce = cce;
    }

    public void run() {
      ConnectionRequest request = null;
      while ((request = (ConnectionRequest) cce.reconnectRequest.take()) != null) {
        if (request.isReconnect()) {
          ClientMessageTransport cmt = request.getClientMessageTransport();
          try {
            cce.reconnect(cmt);
          } catch (MaxConnectionsExceededException e) {
            cmt.logger.warn(e);
            cmt.logger.warn("No longer trying to reconnect.");
            return;
          } catch (Throwable t) {
            cmt.logger.warn("Reconnect failed !", t);
          }
        } else if (request.isRestoreConnection()) {
          RestoreConnectionRequest req = (RestoreConnectionRequest) request;
          cce.restoreConnection(req.getClientMessageTransport(), req.getSocketAddress(), req.getTimeoutMillis(), req
              .getCallback());
        } else if (request.isQuit()) {
          break;
        }
      }
    }
  }

  static class ConnectionRequest {

    public static final int              RECONNECT          = 1;
    public static final int              QUIT               = 2;
    public static final int              RESTORE_CONNECTION = 3;

    private final int                    type;
    private final TCSocketAddress        sa;
    private final ClientMessageTransport cmt;

    public ConnectionRequest(int type, ClientMessageTransport cmt) {
      this(type, cmt, null);
    }

    public ConnectionRequest(final int type, final ClientMessageTransport cmt, final TCSocketAddress sa) {
      this.type = type;
      this.cmt = cmt;
      this.sa = sa;
    }

    public boolean isReconnect() {
      return type == RECONNECT;
    }

    public boolean isQuit() {
      return type == QUIT;
    }

    public boolean isRestoreConnection() {
      return type == RESTORE_CONNECTION;
    }

    public TCSocketAddress getSocketAddress() {
      return sa;
    }

    public ClientMessageTransport getClientMessageTransport() {
      return cmt;
    }
  }

  static class RestoreConnectionRequest extends ConnectionRequest {

    private final RestoreConnectionCallback callback;
    private final long                      timeoutMillis;

    public RestoreConnectionRequest(ClientMessageTransport cmt, final TCSocketAddress sa,
                                    RestoreConnectionCallback callback, long timeoutMillis) {
      super(RESTORE_CONNECTION, cmt, sa);
      this.callback = callback;
      this.timeoutMillis = timeoutMillis;
    }

    public RestoreConnectionCallback getCallback() {
      return callback;
    }

    public long getTimeoutMillis() {
      return timeoutMillis;
    }
  }
}
