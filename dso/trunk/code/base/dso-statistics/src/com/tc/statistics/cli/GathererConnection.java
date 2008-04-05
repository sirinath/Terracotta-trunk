/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.cli;

import com.tc.management.JMXConnectorProxy;
import com.tc.management.beans.L2MBeanNames;
import com.tc.management.beans.TCServerInfoMBean;
import com.tc.statistics.beans.StatisticsLocalGathererMBean;
import com.tc.statistics.beans.StatisticsMBeanNames;

import java.io.IOException;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.remote.JMXConnector;

public class GathererConnection {
  public static final String DEFAULT_HOST = "localhost";
  public static final int DEFAULT_PORT = 9520;

  private String host = DEFAULT_HOST;
  private int port = DEFAULT_PORT;
  private StatisticsLocalGathererMBean gatherer;
  private TCServerInfoMBean info;

  public GathererConnection() {
    //
  }

  public StatisticsLocalGathererMBean getGatherer() {
    return gatherer;
  }

  public String getHost() {
    return host;
  }

  public void setHost(final String host) {
    this.host = host;
  }

  public void setPort(final int port) {
    this.port = port;
  }

  public int getDSOListenPort() {
    return info.getDSOListenPort();
  }

  public void connect() throws IOException {
    JMXConnector jmxc = new JMXConnectorProxy(host, port);

    // create the server connection
    MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();

    // setup the mbeans
    gatherer = (StatisticsLocalGathererMBean)MBeanServerInvocationHandler
      .newProxyInstance(mbsc, StatisticsMBeanNames.STATISTICS_GATHERER, StatisticsLocalGathererMBean.class, true);
    info = (TCServerInfoMBean)MBeanServerInvocationHandler
      .newProxyInstance(mbsc, L2MBeanNames.TC_SERVER_INFO, TCServerInfoMBean.class, false);
  }
}
