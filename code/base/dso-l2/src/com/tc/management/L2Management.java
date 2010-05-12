/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.management;

import com.sun.jmx.remote.generic.DefaultConfig;
import com.sun.jmx.remote.generic.ServerSynchroMessageConnection;
import com.sun.jmx.remote.generic.SynchroCallback;
import com.sun.jmx.remote.generic.SynchroMessageConnectionServer;
import com.sun.jmx.remote.generic.SynchroMessageConnectionServerImpl;
import com.sun.jmx.remote.socket.SocketConnectionServer;
import com.tc.async.api.Sink;
import com.tc.config.schema.setup.L2TVSConfigurationSetupManager;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.CustomerLogging;
import com.tc.logging.JMXLogging;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.beans.L2Dumper;
import com.tc.management.beans.L2MBeanNames;
import com.tc.management.beans.LockStatisticsMonitorMBean;
import com.tc.management.beans.TCDumper;
import com.tc.management.beans.TCServerInfoMBean;
import com.tc.management.beans.object.ObjectManagementMonitor;
import com.tc.net.protocol.tcm.ChannelID;
import com.tc.statistics.StatisticsAgentSubSystem;
import com.tc.statistics.beans.StatisticsMBeanNames;
import com.tc.statistics.beans.impl.StatisticsGatewayMBeanImpl;
import com.tc.util.concurrent.TCExceptionResultException;
import com.tc.util.concurrent.TCFuture;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.generic.GenericConnectorServer;
import javax.management.remote.generic.MessageConnectionServer;
import javax.management.remote.message.MBeanServerRequestMessage;
import javax.management.remote.message.Message;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.management.remote.rmi.RMIJRMPServerImpl;
import javax.security.auth.Subject;

public class L2Management extends TerracottaManagement {

  private static final TCLogger                logger         = TCLogging.getLogger(L2Management.class);

  private MBeanServer                          mBeanServer;
  private JMXConnectorServer                   jmxConnectorServer;
  private final L2TVSConfigurationSetupManager configurationSetupManager;
  private final TCServerInfoMBean              tcServerInfo;
  private final TCDumper                       tcDumper;
  private final ObjectManagementMonitor        objectManagementBean;
  private final LockStatisticsMonitorMBean     lockStatistics;
  private final StatisticsAgentSubSystem       statisticsAgentSubSystem;
  private final StatisticsGatewayMBeanImpl     statisticsGateway;
  private static final Map                     rmiRegistryMap = new HashMap();
  private final int                            jmxPort;
  private final InetAddress                    bindAddress;
  private final Sink                           remoteEventsSink;

  public L2Management(TCServerInfoMBean tcServerInfo, LockStatisticsMonitorMBean lockStatistics,
                      StatisticsAgentSubSystem statisticsAgentSubSystem, StatisticsGatewayMBeanImpl statisticsGateway,
                      L2TVSConfigurationSetupManager configurationSetupManager, TCDumper tcDumper,
                      InetAddress bindAddr, int port, Sink remoteEventsSink) throws MBeanRegistrationException,
      NotCompliantMBeanException, InstanceAlreadyExistsException {
    this.tcServerInfo = tcServerInfo;
    this.lockStatistics = lockStatistics;
    this.configurationSetupManager = configurationSetupManager;
    this.statisticsAgentSubSystem = statisticsAgentSubSystem;
    this.statisticsGateway = statisticsGateway;
    this.tcDumper = tcDumper;
    this.bindAddress = bindAddr;
    this.jmxPort = port;
    this.remoteEventsSink = remoteEventsSink;

    try {
      objectManagementBean = new ObjectManagementMonitor();
    } catch (NotCompliantMBeanException ncmbe) {
      throw new TCRuntimeException(
                                   "Unable to construct one of the L2 MBeans: this is a programming error in one of those beans",
                                   ncmbe);
    }
    // LKC-2990 and LKC-3171: Remove the JMX generic optional logging
    java.util.logging.Logger jmxLogger = java.util.logging.Logger.getLogger("javax.management.remote.generic");
    jmxLogger.setLevel(java.util.logging.Level.OFF);

    // DEV-1304: ClientCommunicatorAdmin uses a different logger
    jmxLogger = java.util.logging.Logger.getLogger("javax.management.remote.misc");
    jmxLogger.setLevel(java.util.logging.Level.OFF);

    final List jmxServers = MBeanServerFactory.findMBeanServer(null);
    if (jmxServers != null && !jmxServers.isEmpty()) {
      mBeanServer = (MBeanServer) jmxServers.get(0);
    } else {
      mBeanServer = MBeanServerFactory.createMBeanServer();
    }
    registerMBeans();
    statisticsGateway.addStatisticsAgent(ChannelID.NULL_ID, mBeanServer);
  }

  /**
   * Keep track of RMI Registries by jmxPort. In 1.5 and forward you can create multiple RMI Registries in a single VM.
   */
  private static Registry getRMIRegistry(int jmxPort, RMIClientSocketFactory csf, RMIServerSocketFactory ssf)
      throws RemoteException {
    Integer key = new Integer(jmxPort);
    Registry registry = (Registry) rmiRegistryMap.get(key);
    if (registry == null) {
      rmiRegistryMap.put(key, registry = LocateRegistry.createRegistry(jmxPort, csf, ssf));
    }
    return registry;
  }

  // DEV-1060
  private static class BindAddrSocketFactory extends RMISocketFactory implements Serializable {
    private final InetAddress bindAddr;

    public BindAddrSocketFactory(InetAddress bindAddress) {
      this.bindAddr = bindAddress;
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
      return new ServerSocket(port, 0, this.bindAddr);
    }

    @Override
    public Socket createSocket(String dummy, int port) throws IOException {
      return new Socket(bindAddr, port);
    }

    @Override
    public int hashCode() {
      return bindAddr.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj == null || getClass() != obj.getClass()) { return false; }

      BindAddrSocketFactory other = (BindAddrSocketFactory) obj;
      return bindAddr.equals(other.bindAddr);
    }
  }

  public synchronized void start() throws Exception {
    JMXServiceURL url;
    Map env = new HashMap();
    String authMsg = "Authentication OFF";
    String credentialsMsg = "";
    env.put("jmx.remote.x.server.connection.timeout", new Long(Long.MAX_VALUE));
    env.put("jmx.remote.server.address.wildcard", "false");
    if (configurationSetupManager.commonl2Config().authentication()) {
      String pwd = configurationSetupManager.commonl2Config().authenticationPasswordFile();
      String loginConfig = configurationSetupManager.commonl2Config().authenticationLoginConfigName();
      String access = configurationSetupManager.commonl2Config().authenticationAccessFile();
      if (pwd != null && !new File(pwd).exists()) CustomerLogging.getConsoleLogger().error(
                                                                                           "Password file does not exist: "
                                                                                               + pwd);
      if (!new File(access).exists()) CustomerLogging.getConsoleLogger().error("Access file does not exist: " + access);
      if (pwd != null) {
        env.put("jmx.remote.x.password.file", pwd);
        credentialsMsg = "Credentials: pwd[" + pwd + "] access[" + access + "]";
      } else if (loginConfig != null) {
        env.put("jmx.remote.x.login.config", loginConfig);
        credentialsMsg = "Credentials: loginConfig[" + loginConfig + "] access[" + access + "]";
      }
      env.put("jmx.remote.x.access.file", access);
      authMsg = "Authentication ON";
      url = new JMXServiceURL("service:jmx:rmi://");
      RMISocketFactory socketFactory = new BindAddrSocketFactory(bindAddress);
      RMIClientSocketFactory csf = bindAddress.isAnyLocalAddress() ? null : socketFactory;
      RMIServerSocketFactory ssf = socketFactory;
      RMIJRMPServerImpl server = new RMIJRMPServerImpl(jmxPort, csf, ssf, env);
      jmxConnectorServer = new RMIConnectorServer(url, env, server, mBeanServer);
      jmxConnectorServer.start();
      getRMIRegistry(jmxPort, csf, ssf).bind("jmxrmi", server);
      String urlHost = bindAddress.getHostAddress();
      CustomerLogging.getConsoleLogger().info(
                                              "JMX Server started. " + authMsg + " - Available at URL["
                                                  + "Service:jmx:rmi:///jndi/rmi://" + urlHost + ":" + jmxPort
                                                  + "/jmxrmi" + "]");
      if (!credentialsMsg.equals("")) CustomerLogging.getConsoleLogger().info(credentialsMsg);
    } else {
      // DEV-1060
      url = new JMXServiceURL("jmxmp", bindAddress.getHostAddress(), jmxPort);

      MessageConnectionServer msgConnectionServer = new SocketConnectionServer(url, env);

      // We use our own connection server classes here so that we can intervene when remote jmx requests come in.
      // Specifically we take every request and instead of processing it directly in the JMX thread, we pass the request
      // off to a thread pool. The whole point of doing is that the JMX runtime likes to use Thread.interrupt() which we
      // don't want happening in our code. Pushing the requests into our own threads provides isolation from these
      // interrupts (see DEV-1955)
      SynchroMessageConnectionServer synchroMessageConnectionServer = new TCSynchroMessageConnectionServer(
                                                                                                           remoteEventsSink,
                                                                                                           msgConnectionServer,
                                                                                                           env);
      env.put(DefaultConfig.SYNCHRO_MESSAGE_CONNECTION_SERVER, synchroMessageConnectionServer);

      jmxConnectorServer = new GenericConnectorServer(env, mBeanServer);
      jmxConnectorServer.start();
      CustomerLogging.getConsoleLogger().info("JMX Server started. Available at URL[" + url + "]");
    }
  }

  public synchronized void stop() throws IOException, InstanceNotFoundException, MBeanRegistrationException {
    unregisterMBeans();
    if (jmxConnectorServer != null) {
      jmxConnectorServer.stop();
    }
  }

  @Override
  public Object findMBean(ObjectName objectName, Class mBeanInterface) throws IOException {
    return findMBean(objectName, mBeanInterface, mBeanServer);
  }

  public MBeanServer getMBeanServer() {
    return mBeanServer;
  }

  public JMXConnectorServer getJMXConnServer() {
    return jmxConnectorServer;
  }

  public ObjectManagementMonitor findObjectManagementMonitorMBean() {
    return objectManagementBean;
  }

  private void registerMBeans() throws MBeanRegistrationException, NotCompliantMBeanException,
      InstanceAlreadyExistsException {
    mBeanServer.registerMBean(tcServerInfo, L2MBeanNames.TC_SERVER_INFO);
    mBeanServer.registerMBean(JMXLogging.getJMXAppender().getMBean(), L2MBeanNames.LOGGER);
    mBeanServer.registerMBean(objectManagementBean, L2MBeanNames.OBJECT_MANAGEMENT);
    mBeanServer.registerMBean(lockStatistics, L2MBeanNames.LOCK_STATISTICS);
    if (statisticsAgentSubSystem.isActive()) {
      statisticsAgentSubSystem.registerMBeans(mBeanServer);
    }
    mBeanServer.registerMBean(statisticsGateway, StatisticsMBeanNames.STATISTICS_GATEWAY);

    mBeanServer.registerMBean(new L2Dumper(tcDumper, mBeanServer), L2MBeanNames.DUMPER);
  }

  protected void unregisterMBeans() throws InstanceNotFoundException, MBeanRegistrationException {
    mBeanServer.unregisterMBean(L2MBeanNames.TC_SERVER_INFO);
    mBeanServer.unregisterMBean(L2MBeanNames.LOGGER);
    mBeanServer.unregisterMBean(L2MBeanNames.OBJECT_MANAGEMENT);
    mBeanServer.unregisterMBean(L2MBeanNames.LOCK_STATISTICS);
    mBeanServer.unregisterMBean(L2MBeanNames.SERVER_DB_BACKUP);
    if (statisticsAgentSubSystem.isActive()) {
      statisticsAgentSubSystem.unregisterMBeans(mBeanServer);
    }
    mBeanServer.unregisterMBean(StatisticsMBeanNames.STATISTICS_GATEWAY);

    mBeanServer.unregisterMBean(L2MBeanNames.DUMPER);

  }
  
  public static class TCSynchroMessageConnectionServer extends SynchroMessageConnectionServerImpl {

    private final Sink remoteEventsSink;

    public TCSynchroMessageConnectionServer(Sink remoteEventsSink, MessageConnectionServer msServer, Map env) {
      super(msServer, env);
      this.remoteEventsSink = remoteEventsSink;
    }

    @Override
    public ServerSynchroMessageConnection accept() throws IOException {
      return new ServerSynchroMessageConnectionWrapper(remoteEventsSink, super.accept());
    }
  }

  private static class ServerSynchroMessageConnectionWrapper implements ServerSynchroMessageConnection {

    private final ServerSynchroMessageConnection conn;
    private final Sink                           queue;

    public ServerSynchroMessageConnectionWrapper(Sink queue, ServerSynchroMessageConnection conn) {
      this.queue = queue;
      this.conn = conn;
    }

    public void close() throws IOException {
      conn.close();
    }

    public void connect(Map env) throws IOException {
      conn.connect(env);
    }

    public String getConnectionId() {
      return conn.getConnectionId();
    }

    public Subject getSubject() {
      return conn.getSubject();
    }

    public void sendOneWay(Message msg) throws IOException, UnsupportedOperationException {
      conn.sendOneWay(msg);
    }

    public void setCallback(SynchroCallback cb) {
      conn.setCallback(new SynchroCallbackWrapper(queue, cb, getConnectionId()));
    }
  }

  private static class SynchroCallbackWrapper implements SynchroCallback {

    private final SynchroCallback callback;
    private final Sink            remoteEventsSink;
    private final String          connectionId;

    public SynchroCallbackWrapper(Sink remoteEventsSink, SynchroCallback cb, String connectionId) {
      this.remoteEventsSink = remoteEventsSink;
      this.callback = cb;
      this.connectionId = connectionId;
    }

    public void connectionException(Exception ie) {
      callback.connectionException(ie);
    }

    public Message execute(Message request) {
      if (request instanceof MBeanServerRequestMessage) {
        // log shutdown call
        MBeanServerRequestMessage msrm = (MBeanServerRequestMessage) request;
        Object params[] = msrm.getParams();
        if (params != null && params.length > 1 && "shutdown".equals(params[1])) {
          StringBuilder buf = new StringBuilder();
          buf.append("JMX shutdown request connectionId:" + connectionId + " execute methodId: " + msrm.getMethodId()
                     + " params:");
          for (Object o : params) {
            buf.append(" " + o);
          }
          logger.info(buf);
        }
        TCFuture future = new TCFuture();
        remoteEventsSink.add(new CallbackExecuteContext(Thread.currentThread().getContextClassLoader(), callback,
                                                        request, future));

        try {
          return (Message) future.get();
        } catch (InterruptedException e) {
          logger.debug("remote JMX call interrupted");
          return null;
        } catch (TCExceptionResultException e) {
          throw new RuntimeException(e.getCause());
        }
      }

      return callback.execute(request);

    }
  }

}
