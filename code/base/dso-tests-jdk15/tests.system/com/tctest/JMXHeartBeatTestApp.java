/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import com.tc.management.beans.L2MBeanNames;
import com.tc.management.beans.TCServerInfoMBean;
import com.tc.object.config.ConfigVisitor;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tc.util.Assert;
import com.tc.util.concurrent.ThreadUtil;
import com.tctest.runner.AbstractTransparentApp;

import java.io.IOException;
import java.util.concurrent.CyclicBarrier;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

public class JMXHeartBeatTestApp extends AbstractTransparentApp {

  public static final String      CONFIG_FILE      = "config-file";
  public static final String      PORT_NUMBER      = "port-number";
  public static final String      HOST_NAME        = "host-name";
  public static final String      JMX_PORT         = "jmx-port";

  private final ApplicationConfig config;

  private final int               initialNodeCount = getParticipantCount();
  private final CyclicBarrier     stage1           = new CyclicBarrier(initialNodeCount);

  private MBeanServerConnection   mbsc             = null;
  private JMXConnector            jmxc;
  private TCServerInfoMBean       serverMBean;

  public JMXHeartBeatTestApp(String appId, ApplicationConfig config, ListenerProvider listenerProvider) {
    super(appId, config, listenerProvider);
    this.config = config;
  }

  public static void visitL1DSOConfig(ConfigVisitor visitor, DSOClientConfigHelper config) {

    String testClass = JMXHeartBeatTestApp.class.getName();
    String methodExpression = "* " + testClass + "*.*(..)";
    config.addWriteAutolock(methodExpression);
    TransparencyClassSpec spec = config.getOrCreateSpec(testClass);
    config.addIncludePattern(testClass + "$*");

    // roots
    spec.addRoot("stage1", "stage1");
  }

  public void run() {
    try {
      runTest();
    } catch (Throwable t) {
      notifyError(t);
    }
  }

  private boolean isServerAlive() {
    boolean isAlive = false;

    try {
      String theUrl = "service:jmx:rmi:///jndi/rmi://localhost:" + config.getAttribute(JMX_PORT) + "/jmxrmi";
      JMXServiceURL url = new JMXServiceURL(theUrl);
      echo("connecting to jmx server....");
      jmxc = JMXConnectorFactory.connect(url, null);
      mbsc = jmxc.getMBeanServerConnection();
      echo("obtained mbeanserver connection");
      serverMBean = (TCServerInfoMBean) MBeanServerInvocationHandler.newProxyInstance(mbsc,
                                                                                      L2MBeanNames.TC_SERVER_INFO,
                                                                                      TCServerInfoMBean.class, false);
      String result = serverMBean.getHealthStatus();
      echo("got health status: " + result);
      jmxc.close();
      isAlive = result.startsWith("OK");
    } catch (Throwable e) {
      e.printStackTrace();
    } finally {
      if (jmxc != null) {
        try {
          jmxc.close();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    return isAlive;
  }

  private void runTest() throws Throwable {

    Assert.assertEquals(true, isServerAlive());
    echo("Server is alive");
    echo("About to crash server...");
    config.getServerControl().crash();
    // has to sleep longer than l1-reconnect timeout
    ThreadUtil.reallySleep(30 * 1000);
    Assert.assertEquals(false, isServerAlive());
    echo("Server is crashed.");
    echo("About to restart server");
    config.getServerControl().start();
    echo("Server restarted.");
    stage1.await();
    echo("Server restarted successfully.");
    Assert.assertEquals(true, isServerAlive());
  }

  private static void echo(String msg) {
    System.out.println(msg);
  }

}
