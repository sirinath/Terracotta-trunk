/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.management;

import com.sun.jmx.remote.opt.util.EnvHelp;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.beans.L1Dumper;
import com.tc.management.beans.L1MBeanNames;
import com.tc.management.beans.MBeanNames;
import com.tc.management.beans.l1.L1InfoMBean;
import com.tc.management.beans.logging.InstrumentationLogging;
import com.tc.management.beans.logging.InstrumentationLoggingMBean;
import com.tc.management.beans.logging.RuntimeLogging;
import com.tc.management.beans.logging.RuntimeLoggingMBean;
import com.tc.management.beans.logging.RuntimeOutputOptions;
import com.tc.management.beans.logging.RuntimeOutputOptionsMBean;
import com.tc.management.beans.sessions.SessionMonitor;
import com.tc.management.beans.sessions.SessionMonitorImpl;
import com.tc.management.beans.tx.ClientTxMonitor;
import com.tc.management.beans.tx.ClientTxMonitorMBean;
import com.tc.management.exposed.TerracottaCluster;
import com.tc.management.remote.protocol.ProtocolProvider;
import com.tc.management.remote.protocol.terracotta.TunnelingEventHandler;
import com.tc.management.remote.protocol.terracotta.TunnelingMessageConnectionServer;
import com.tc.object.config.MBeanSpec;
import com.tc.object.logging.InstrumentationLogger;
import com.tc.object.logging.RuntimeLogger;
import com.tc.statistics.StatisticsAgentSubSystemImpl;
import com.tc.util.concurrent.SetOnceFlag;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

public final class L1Management extends TerracottaManagement {

  private static final TCLogger              logger = TCLogging.getLogger(L1Management.class);

  private final SetOnceFlag                  started;
  private final TunnelingEventHandler        tunnelingHandler;
  private final Object                       mBeanServerLock;
  private MBeanServer                        mBeanServer;

  private final ClientTxMonitor              clientTxBean;
  private final SessionMonitor               httpSessionsMonitor;
  private final TerracottaCluster            clusterBean;
  private final L1Info                       l1InfoBean;
  private final InstrumentationLogging       instrumentationLoggingBean;
  private final RuntimeOutputOptions         runtimeOutputOptionsBean;
  private final RuntimeLogging               runtimeLoggingBean;

  private final StatisticsAgentSubSystemImpl statisticsAgentSubSystem;

  private final MBeanSpec[]                  mbeanSpecs;

  private final L1Dumper                     l1DumpBean;

  public L1Management(final TunnelingEventHandler tunnelingHandler,
                      final StatisticsAgentSubSystemImpl statisticsAgentSubSystem, final RuntimeLogger runtimeLogger,
                      final InstrumentationLogger instrumentationLogger, final String rawConfigText,
                      final TCClient client, final MBeanSpec[] mbeanSpecs) {
    super();

    started = new SetOnceFlag();
    this.tunnelingHandler = tunnelingHandler;
    this.statisticsAgentSubSystem = statisticsAgentSubSystem;
    this.mbeanSpecs = mbeanSpecs;

    try {
      l1DumpBean = new L1Dumper(client);
      clientTxBean = new ClientTxMonitor();
      httpSessionsMonitor = new SessionMonitorImpl();
      clusterBean = new TerracottaCluster();
      l1InfoBean = new L1Info(client, rawConfigText);
      instrumentationLoggingBean = new InstrumentationLogging(instrumentationLogger);
      runtimeOutputOptionsBean = new RuntimeOutputOptions(runtimeLogger);
      runtimeLoggingBean = new RuntimeLogging(runtimeLogger);
    } catch (NotCompliantMBeanException ncmbe) {
      throw new TCRuntimeException(
                                   "Unable to construct one of the L1 MBeans: this is a programming error in one of those beans",
                                   ncmbe);
    }
    mBeanServerLock = new Object();
  }

  public synchronized void start(final boolean createDedicatedMBeanServer) {
    started.set();

    Thread registrationThread = new Thread(new Runnable() {

      private final int MAX_ATTEMPTS = 60 * 5;

      public void run() {
        boolean registered = false;
        int attemptCounter = 0;
        while (!registered && attemptCounter++ < MAX_ATTEMPTS) {
          try {
            if (logger.isDebugEnabled()) {
              logger.debug("Attempt #" + (attemptCounter + 1) + " to find the MBeanServer and register the L1 MBeans");
            }
            attemptToRegister(createDedicatedMBeanServer);
            registered = true;
            if (logger.isDebugEnabled()) {
              logger.debug("L1 MBeans registered with the MBeanServer successfully after " + (attemptCounter + 1)
                           + " attempts");
            }
          } catch (InstanceAlreadyExistsException e) {
            logger
                .error("Exception while registering the L1 MBeans, they already seem to exist in the MBeanServer.", e);
            return;
          } catch (Exception e) {
            // Ignore and try again after 1 second, give the VM a chance to get started
            if (logger.isDebugEnabled()) {
              logger.debug("Caught exception while trying to register L1 MBeans", e);
            }
            try {
              Thread.sleep(1000);
            } catch (InterruptedException ie) {
              new Exception("JMX registration thread interrupted, management beans will not be available", ie)
                  .printStackTrace();
            }
          }
        }
        if (registered) {
          tunnelingHandler.jmxIsReady();
        } else {
          logger.error("Aborted attempts to register management" + " beans after " + (MAX_ATTEMPTS / 60)
                       + " min of trying.");
        }
      }
    }, "L1Management JMX registration");
    registrationThread.setDaemon(true);
    registrationThread.start();
  }

  @Override
  public Object findMBean(final ObjectName objectName, final Class mBeanInterface) throws IOException {
    if (objectName.equals(MBeanNames.CLIENT_TX_INTERNAL)) return clientTxBean;
    else if (objectName.equals(L1MBeanNames.HTTP_SESSIONS_PUBLIC)) return httpSessionsMonitor;
    else if (objectName.equals(L1MBeanNames.L1INFO_PUBLIC)) return l1InfoBean;
    else if (objectName.equals(L1MBeanNames.INSTRUMENTATION_LOGGING_PUBLIC)) return instrumentationLoggingBean;
    else if (objectName.equals(L1MBeanNames.RUNTIME_OUTPUT_OPTIONS_PUBLIC)) return runtimeOutputOptionsBean;
    else if (objectName.equals(L1MBeanNames.RUNTIME_LOGGING_PUBLIC)) return runtimeLoggingBean;
    else {
      synchronized (mBeanServerLock) {
        if (mBeanServer != null) { return findMBean(objectName, mBeanInterface, mBeanServer); }
      }
    }
    return null;
  }

  public ClientTxMonitorMBean findClientTxMonitorMBean() {
    return clientTxBean;
  }

  public SessionMonitor getHttpSessionMonitor() {
    return httpSessionsMonitor;
  }

  public L1InfoMBean findL1InfoMBean() {
    return l1InfoBean;
  }

  public InstrumentationLoggingMBean findInstrumentationLoggingMBean() {
    return instrumentationLoggingBean;
  }

  public RuntimeOutputOptionsMBean findRuntimeOutputOptionsMBean() {
    return runtimeOutputOptionsBean;
  }

  public RuntimeLoggingMBean findRuntimeLoggingMBean() {
    return runtimeLoggingBean;
  }

  private void attemptToRegister(final boolean createDedicatedMBeanServer) throws InstanceAlreadyExistsException,
      MBeanRegistrationException, NotCompliantMBeanException {
    synchronized (mBeanServerLock) {
      if (mBeanServer == null) {
        if (createDedicatedMBeanServer) {
          if (logger.isDebugEnabled()) {
            logger.debug("attemptToRegister(): Creating an MBeanServer since explicitly requested");
          }
          mBeanServer = MBeanServerFactory.createMBeanServer();
        } else {
          mBeanServer = getPlatformDefaultMBeanServer();
        }
        addJMXConnectors();
      }
    }

    mBeanServer.registerMBean(l1DumpBean, MBeanNames.L1DUMPER_INTERNAL);
    mBeanServer.registerMBean(clientTxBean, MBeanNames.CLIENT_TX_INTERNAL);
    mBeanServer.registerMBean(httpSessionsMonitor, L1MBeanNames.HTTP_SESSIONS_PUBLIC);
    mBeanServer.registerMBean(clusterBean, L1MBeanNames.CLUSTER_BEAN_PUBLIC);
    if (statisticsAgentSubSystem.isActive()) {
      statisticsAgentSubSystem.registerMBeans(mBeanServer);
    }
    mBeanServer.registerMBean(l1InfoBean, L1MBeanNames.L1INFO_PUBLIC);
    mBeanServer.registerMBean(instrumentationLoggingBean, L1MBeanNames.INSTRUMENTATION_LOGGING_PUBLIC);
    mBeanServer.registerMBean(runtimeOutputOptionsBean, L1MBeanNames.RUNTIME_OUTPUT_OPTIONS_PUBLIC);
    mBeanServer.registerMBean(runtimeLoggingBean, L1MBeanNames.RUNTIME_LOGGING_PUBLIC);
    if (mbeanSpecs != null) {
      for (MBeanSpec spec : mbeanSpecs) {
        for (Map.Entry<ObjectName, Object> bean : spec.getMBeans().entrySet()) {
          mBeanServer.registerMBean(bean.getValue(), bean.getKey());
        }
      }
    }
  }
  
  public MBeanServer getMBeanServer() {
    return mBeanServer;
  }

  private void addJMXConnectors() {
    JMXServiceURL url = null;
    try {
      // LKC-2990 and LKC-3171: Remove the JMX generic optional logging
      java.util.logging.Logger jmxLogger = java.util.logging.Logger.getLogger("javax.management.remote.generic");
      jmxLogger.setLevel(java.util.logging.Level.OFF);
    } catch (Throwable t) {
      logger.warn("Unable to disable default logging in Sun's JMX package; when Terracotta clients go"
                  + " up/down you may see stack traces printed to the log");
    }
    try {
      final Map environment = new HashMap();
      environment.put("jmx.remote.x.server.connection.timeout", new Long(Long.MAX_VALUE));
      ProtocolProvider.addTerracottaJmxProvider(environment);
      environment.put(TunnelingMessageConnectionServer.TUNNELING_HANDLER, tunnelingHandler);
      environment.put(EnvHelp.SERVER_CONNECTION_TIMEOUT, String.valueOf(Long.MAX_VALUE));
      url = new JMXServiceURL("terracotta", "localhost", 0);
      // Normally you should NOT do this in the client, but we have a modified version of jmxremote_optional.jar that
      // uses a daemon thread to wait for connections so we don't hang the client
      final JMXConnectorServer connServer = JMXConnectorServerFactory.newJMXConnectorServer(url, environment,
                                                                                            mBeanServer);
      connServer.start();
      logger.info("Terracotta JMX connector available at[" + url + "]");
    } catch (Exception e) {
      if (url != null) {
        logger.warn("Unable to start embedded JMX connector for url[" + url + "]", e);
      } else {
        logger.warn("Unable to construct embedded JMX connector URL with params (terracotta, localhost, 0)");
      }
    }
  }

  private MBeanServer getPlatformDefaultMBeanServer() {
    return ManagementFactory.getPlatformMBeanServer();
  }

}