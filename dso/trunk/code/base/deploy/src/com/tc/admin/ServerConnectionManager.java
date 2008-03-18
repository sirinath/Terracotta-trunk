/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import org.apache.commons.lang.StringUtils;

import com.tc.config.schema.L2Info;
import com.tc.management.JMXConnectorProxy;

import java.io.IOException;
import java.rmi.ConnectException;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.AttributeChangeNotification;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXServiceURL;

public class ServerConnectionManager implements NotificationListener {
  private L2Info                             m_l2Info;
  private boolean                            m_autoConnect;
  private ConnectionContext                  m_connectCntx;
  private ConnectionListener                 m_connectListener;
  private JMXConnectorProxy                  m_jmxConnector;
  private HashMap<String, Object>            m_connectEnv;
  private ServerHelper                       m_serverHelper;
  private boolean                            m_connected;
  private boolean                            m_started;
  private boolean                            m_active;
  private boolean                            m_passiveUninitialized;
  private boolean                            m_passiveStandby;
  private Exception                          m_connectException;
  private ConnectThread                      m_connectThread;
  private ConnectionMonitorAction            m_connectMonitorAction;
  private Timer                              m_connectMonitorTimer;
  private AutoConnectListener                m_autoConnectListener;
  
  private static final Map<String, String[]> m_credentialsMap       = new HashMap<String, String[]>();

  private static final int                   CONNECT_MONITOR_PERIOD = 1000;

  private static final Object                m_connectTestLock      = new Object();

  static {
    String levelName = System.getProperty("ServerConnectionManager.logLevel");
    Level level = Level.OFF;

    if (levelName != null) {
      try {
        level = Level.parse(levelName);
      } catch (IllegalArgumentException ie) {
        level = Level.ALL;
      }
    }
    Logger.getLogger("javax.management.remote").setLevel(level);
  }

  public ServerConnectionManager(String host, int port, boolean autoConnect, ConnectionListener listener) {
    this(new L2Info(host, host, port), autoConnect, listener);
  }

  public ServerConnectionManager(L2Info l2Info, boolean autoConnect, ConnectionListener listener) {
    m_autoConnect = autoConnect;
    m_connectListener = listener;
    m_serverHelper = ServerHelper.getHelper();

    setL2Info(l2Info);
  }

  public void setConnectionListener(ConnectionListener listener) {
    m_connectListener = listener;
  }
  
  public ConnectionListener getConnectionListener() {
    return m_connectListener;
  }
  
  public L2Info getL2Info() {
    return m_l2Info;
  }

  private void resetConnectedState() {
    m_active = m_started = m_passiveUninitialized = m_passiveStandby = false;
  }
  
  private void resetAllState() {
    m_connected = false;
    resetConnectedState();
  }
  
  public void setL2Info(L2Info l2Info) {
    cancelActiveServices();
    resetAllState();

    m_l2Info = l2Info;
    m_connectCntx = new ConnectionContext(l2Info);

    try {
      if (isAutoConnect()) {
        startConnect();
      }
    } catch (Exception e) {/**/
    }
  }

  public void setHostname(String hostname) {
    setL2Info(new L2Info(m_l2Info.name(), hostname, m_l2Info.jmxPort()));
  }

  public void setJMXPortNumber(int port) {
    setL2Info(new L2Info(m_l2Info.name(), m_l2Info.host(), port));
  }

  public void setCredentials(String username, String password) {
    Map<String, Object> connEnv = getConnectionEnvironment();
    connEnv.put("jmx.remote.credentials", new String[] { username, password });
  }

  public String[] getCredentials() {
    Map<String, Object> connEnv = getConnectionEnvironment();
    return (String[]) connEnv.get("jmx.remote.credentials");
  }

  static void cacheCredentials(ServerConnectionManager scm, String[] credentials) {
    m_credentialsMap.put(scm.toString(), credentials);
    m_credentialsMap.put(scm.getHostname(), credentials);
  }

  public static String[] getCachedCredentials(ServerConnectionManager scm) {
    String[] result = m_credentialsMap.get(scm.toString());
    if (result == null) {
      result = m_credentialsMap.get(scm.getHostname());
    }
    return result;
  }

  public void setAutoConnect(boolean autoConnect) {
    if (m_autoConnect != autoConnect) {
      if ((m_autoConnect = autoConnect) == true) {
        if (!m_connected) {
          startConnect();
        }
      } else {
        cancelActiveServices();
      }
    }
  }

  public boolean isAutoConnect() {
    return m_autoConnect;
  }

  public void setConnectionContext(ConnectionContext cc) {
    m_connectCntx = cc;
  }

  public ConnectionContext getConnectionContext() {
    return m_connectCntx;
  }

  public void setJMXConnector(JMXConnector jmxc) throws IOException {
    m_connectException = null;
    m_connectCntx.jmxc = jmxc;
    m_connectCntx.mbsc = jmxc.getMBeanServerConnection();
    setConnected(true);
  }

  protected void setConnected(boolean connected) {
    if (m_connected != connected) {
      synchronized (this) {
        _setConnected(connected);
        if (isConnected() == false) {
          cancelActiveServices();
          resetConnectedState();
        } else {
          cacheCredentials(ServerConnectionManager.this, getCredentials());
          m_started = true;
          if ((m_active = internalIsActive()) == false) {
            if ((m_passiveUninitialized = internalIsPassiveUninitialized()) == false) {
              m_passiveStandby = internalIsPassiveStandby();
            }
            addActivationListener();
          }
        }
      }

      // Notify listener that the connection state changed.
      if (m_connectListener != null) {
        m_connectListener.handleConnection();
      }
      
      if(connected) {
        initConnectionMonitor();
      } else if (isAutoConnect()) {
        startConnect();
      }
    }
  }

  /**
   * Mark not-connected, notify, cancel connection monitor, don't startup auto-connect thread.
   */
  void disconnectOnExit() {
    cancelActiveServices();
    if (isConnected()) {
      _setConnected(false);
      if (m_connectListener != null) {
        m_connectListener.handleConnection();
      }
    }
  }

  /**
   * Since we have all of this infrastructure, turn off the JMXRemote connection monitoring stuff.
   */
  public Map<String, Object> getConnectionEnvironment() {
    if (m_connectEnv == null) {
      m_connectEnv = new HashMap<String, Object>();
      m_connectEnv.put("jmx.remote.x.client.connection.check.period", new Long(0));
      m_connectEnv.put("jmx.remote.default.class.loader", getClass().getClassLoader());
    }
    return m_connectEnv;
  }

  private void initConnector() {
    if (m_jmxConnector != null) {
      try {
        m_jmxConnector.close();
      } catch (Exception e) {/**/
      }
    }
    m_jmxConnector = new JMXConnectorProxy(getHostname(), getJMXPortNumber(), getConnectionEnvironment());
  }

  public JMXConnector getJmxConnector() {
    initConnector();
    return m_jmxConnector;
  }

  private void startConnect() {
    try {
      cancelConnectThread();
      initConnector();
      m_connectThread = new ConnectThread();
      m_connectThread.start();
    } catch (Exception e) {
      m_connectException = e;
      if (m_connectListener != null) {
        m_connectListener.handleException();
      }
    }
  }

  private void cancelConnectThread() {
    if (m_connectThread != null && m_connectThread.isAlive()) {
      try {
        m_connectThread.cancel();
        m_connectThread = null;
      } catch (Exception ignore) {/**/
      }
    }
  }

  static boolean isConnectException(IOException ioe) {
    Throwable t = ioe;

    while (t != null) {
      if (t instanceof ConnectException) { return true; }
      t = t.getCause();
    }

    return false;
  }

  public boolean testIsConnected() throws Exception {
    if (m_connectCntx == null) return false;

    synchronized (m_connectTestLock) {
      if (m_connectCntx == null) return false;

      if (m_connectCntx.jmxc == null) {
        initConnector();
      }

      m_jmxConnector.connect(getConnectionEnvironment());
      m_connectCntx.mbsc = m_jmxConnector.getMBeanServerConnection();
      m_connectCntx.jmxc = m_jmxConnector;
      m_connectException = null;

      return true;
    }
  }

  class ConnectThread extends Thread {
    private boolean m_cancel = false;

    ConnectThread() {
      super();
      setPriority(MIN_PRIORITY);
    }

    public void run() {
      try {
        sleep(500);
      } catch (InterruptedException ie) {/**/
      }

      while (!m_cancel && !m_connected) {
        try {
          boolean isConnected = testIsConnected();
          if (!m_cancel) {
            setConnected(isConnected);
          }
          return;
        } catch (Exception e) {
          if (m_cancel) {
            return;
          } else {
            m_connectException = e;
            if (m_connectListener != null) {
              if (e instanceof SecurityException) {
                setAutoConnect(false);
                fireToggleAutoConnectEvent();
                m_connectListener.handleException();
                return;
              }
              m_connectListener.handleException();
            }
          }
        }

        try {
          sleep(2000);
        } catch (InterruptedException ie) {
          // We may interrupt the connect thread when a new host or port comes in
          // because we have to recreate the connection context, JMX service URL,
          // and connect thread.
          return;
        }
      }
    }

    void cancel() {
      m_cancel = true;
    }
  }

  void addToggleAutoConnectListener(AutoConnectListener listener) {
    m_autoConnectListener = listener;
  }

  private void fireToggleAutoConnectEvent() {
    if (m_autoConnectListener != null) m_autoConnectListener.handleEvent();
  }

  JMXServiceURL getJMXServiceURL() {
    return m_jmxConnector.getServiceURL();
  }

  public String getName() {
    return m_l2Info.name();
  }

  public String getHostname() {
    return m_l2Info.host();
  }

  public int getJMXPortNumber() {
    return m_l2Info.jmxPort();
  }

  private void _setConnected(boolean connected) {
    m_connected = connected;
  }
  
  public boolean isConnected() {
    return m_connected;
  }

  public Exception getConnectionException() {
    return m_connectException;
  }

  public boolean testIsActive() {
    return internalIsActive();
  }
  
  public boolean isActive() {
    return m_active;
  }

  public boolean canShutdown() {
    try {
      return m_serverHelper.canShutdown(m_connectCntx);
    } catch (Exception e) {
      return false;
    }
  }
  
  private boolean internalIsActive() {
    try {
      return m_serverHelper.isActive(m_connectCntx);
    } catch (Exception e) {
      return false;
    }
  }

  public boolean isStarted() {
    return m_started;
  }

  private boolean internalIsPassiveUninitialized() {
    try {
      return m_serverHelper.isPassiveUninitialized(m_connectCntx);
    } catch (Exception e) {
      return false;
    }
  }

  public boolean isPassiveUninitialized() {
    return m_passiveUninitialized;
  }

  private boolean internalIsPassiveStandby() {
    try {
      return m_serverHelper.isPassiveStandby(m_connectCntx);
    } catch (Exception e) {
      return false;
    }
  }

  public boolean isPassiveStandby() {
    return m_passiveStandby;
  }

  void initConnectionMonitor() {
    if (m_connectMonitorAction == null) {
      m_connectMonitorAction = new ConnectionMonitorAction();
    }
    if (m_connectMonitorTimer == null) {
      m_connectMonitorTimer = new Timer();
      m_connectMonitorTimer.schedule(m_connectMonitorAction, CONNECT_MONITOR_PERIOD, CONNECT_MONITOR_PERIOD);
    }
  }

  private class ConnectionMonitorAction extends TimerTask {
    public void run() {
      if (m_connectCntx != null && m_connectCntx.isConnected()) {
        try {
          m_connectCntx.testConnection();
        } catch (Exception e) {
          cancelConnectionMonitor();
          setConnected(false);
        }
      }
    }
  }

  void cancelConnectionMonitor() {
    if (m_connectMonitorTimer != null) {
      m_connectMonitorTimer.cancel();
      m_connectMonitorAction.cancel();
      m_connectMonitorAction = null;
      m_connectMonitorTimer = null;
    }
  }

  /**
   * Register for a JMX callback when the server transitions from started->...->active. We do this when we notice that
   * the server is started but not yet active.
   */
  void addActivationListener() {
    try {
      ObjectName infoMBean = m_serverHelper.getServerInfoMBean(m_connectCntx);
      m_connectCntx.addNotificationListener(infoMBean, this);
      if ((m_active = internalIsActive()) == true) {
        m_connectCntx.removeNotificationListener(infoMBean, this);
      }
    } catch (Exception e) {/**/
    }
  }

  void removeActivationListener() {
    try {
      ObjectName infoMBean = m_serverHelper.getServerInfoMBean(m_connectCntx);
      m_connectCntx.removeNotificationListener(infoMBean, this);
    } catch (Exception e) {/**/
    }
  }

  /**
   * JMX callback notifying that the server has transitioned from started->active.
   */
  public void handleNotification(Notification notice, Object handback) {
    if (notice instanceof AttributeChangeNotification) {
      AttributeChangeNotification acn = (AttributeChangeNotification) notice;

      if (acn.getAttributeType().equals("jmx.terracotta.L2.active")) {
        m_active = true;
        removeActivationListener();
        if (m_connectListener != null) {
          m_connectListener.handleConnection();
        }
      } else if (acn.getAttributeType().equals("jmx.terracotta.L2.passive-uninitialized")) {
        m_passiveUninitialized = true;
        if (m_connectListener != null) {
          m_connectListener.handleConnection();
        }
      } else if (acn.getAttributeType().equals("jmx.terracotta.L2.passive-standby")) {
        m_passiveUninitialized = false;
        m_passiveStandby = true;
        if (m_connectListener != null) {
          m_connectListener.handleConnection();
        }
      }
    }
  }

  public String toString() {
    return getHostname() + ":" + getJMXPortNumber();
  }

  public String getStatusString() {
    StringBuffer sb = new StringBuffer(this.toString());
    sb.append(":");
    sb.append("connected=");
    sb.append(m_connected);
    if (m_connected) {
      sb.append(",status=");
      if (m_active) sb.append("active");
      else if (m_passiveUninitialized) sb.append("passive-uninitialized");
      else if (m_passiveStandby) sb.append("passive-standby");
      else if (m_started) sb.append("started");
      else sb.append("none");
    }
    return sb.toString();
  }

  public void dump(String prefix) {
    System.out.println(prefix + this + ":connected=" + m_connected + ",autoConnect=" + m_autoConnect + ",started="
                       + m_started + ",exception=" + m_connectException);
  }

  void cancelActiveServices() {
    cancelConnectThread();
    cancelConnectionMonitor();

    if (m_started) {
      removeActivationListener();
    }
    if (m_connectCntx != null) {
      m_connectCntx.reset();
    }
  }

  public void tearDown() {
    cancelActiveServices();

    m_l2Info = null;
    m_serverHelper = null;
    m_connectCntx = null;
    m_connectListener = null;
    m_connectThread = null;
  }

  public boolean equals(Object o) {
    if(!(o instanceof ServerConnectionManager)) return false;
    
    ServerConnectionManager other = (ServerConnectionManager)o;
    String otherHostname = other.getHostname();
    int otherJMXPort = other.getJMXPortNumber();
    String hostname = getHostname();
    int jmxPort = getJMXPortNumber();
    
    return otherJMXPort == jmxPort && StringUtils.equals(otherHostname, hostname);
  }
  
  // --------------------------------------------------------------------------------

  public static interface AutoConnectListener extends EventListener {
    void handleEvent();
  }
}
