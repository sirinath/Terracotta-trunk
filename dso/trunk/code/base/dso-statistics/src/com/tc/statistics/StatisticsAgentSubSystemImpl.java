/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics;

import com.tc.config.schema.NewStatisticsConfig;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.statistics.beans.StatisticsEmitterMBean;
import com.tc.statistics.beans.StatisticsMBeanNames;
import com.tc.statistics.beans.impl.StatisticsEmitterMBeanImpl;
import com.tc.statistics.beans.impl.StatisticsManagerMBeanImpl;
import com.tc.statistics.buffer.StatisticsBuffer;
import com.tc.statistics.buffer.exceptions.TCStatisticsBufferException;
import com.tc.statistics.buffer.h2.H2StatisticsBufferImpl;
import com.tc.statistics.config.StatisticsConfig;
import com.tc.statistics.config.impl.StatisticsConfigImpl;
import com.tc.statistics.retrieval.StatisticsRetrievalRegistry;
import com.tc.statistics.retrieval.impl.StatisticsRetrievalRegistryImpl;

import java.io.File;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;

public class StatisticsAgentSubSystemImpl implements StatisticsAgentSubSystem {
  private final static TCLogger logger        = CustomerLogging.getDSOGenericLogger();
  private final static TCLogger consoleLogger = CustomerLogging.getConsoleLogger();

  private volatile StatisticsBuffer            statisticsBuffer;
  private volatile StatisticsEmitterMBean      statisticsEmitterMBean;
  private volatile StatisticsManagerMBeanImpl  statisticsManagerMBean;
  private volatile StatisticsRetrievalRegistry statisticsRetrievalRegistry;

  private volatile boolean active = false;

  public boolean isActive() {
    return active;
  }

  public void setDefaultAgentIp(final String defaultAgentIp) {
    if (null == statisticsBuffer) throw new AssertionError("The statistics subsystem has to be setup before.");
    statisticsBuffer.setDefaultAgentIp(defaultAgentIp);
  }

  public void setDefaultAgentDifferentiator(final String defaultAgentDifferentiator) {
    if (null == statisticsBuffer) throw new AssertionError("The statistics subsystem has to be setup before.");
    statisticsBuffer.setDefaultAgentDifferentiator(defaultAgentDifferentiator);
  }

  public synchronized boolean setup(final NewStatisticsConfig config) {
    StatisticsConfig statistics_config = new StatisticsConfigImpl();
    
    // create the statistics buffer
    File stat_path = config.statisticsPath().getFile();
    try {
      stat_path.mkdirs();
    } catch (Exception e) {
      // TODO: needs to be properly written and put in a properties file
      String msg =
        "\n**************************************************************************************\n"
        + "Unable to create the directory '" + stat_path.getAbsolutePath() + "' for the statistics buffer.\n"
        + "The CVT system will not be active for this node.\n"
        + "**************************************************************************************\n";
      consoleLogger.error(msg);
      logger.error(msg, e);
      return false;
    }
    try {
      statisticsBuffer = new H2StatisticsBufferImpl(statistics_config, stat_path);
      statisticsBuffer.open();
    } catch (TCStatisticsBufferException e) {
      // TODO: needs to be properly written and put in a properties file
      String msg =
        "\n**************************************************************************************\n"
        + "The statistics buffer couldn't be opened at \n"
        + "'" + stat_path.getAbsolutePath() + "'.\n"
        + "The CVT system will not be active for this node.\n"
        + "\n"
        + "A common reason for this is that you're launching several Terracotta L1\n"
        + "clients on the same machine. The default directory for the statistics buffer\n"
        + "uses the IP address of the machine that it runs on as the identifier.\n"
        + "When several clients are being executed on the same machine, a typical solution\n"
        + "to properly separate these directories is by using a JVM property at startup\n"
        + "that is unique for each client.\n"
        + "\n"
        + "For example:\n"
        + "  dso-java.sh -Dtc.node-name=node1 your.main.Class\n"
        + "\n"
        + "You can then adapt the tc-config.xml file so that this JVM property is picked\n"
        + "up when the statistics directory is configured by using %(tc.node-name) in the\n"
        + "statistics path.\n"
        + "**************************************************************************************\n";
      consoleLogger.error(msg);
      logger.error(msg, e);
      return false;
    }
    String infoMsg = "Statistics buffer: '" + stat_path.getAbsolutePath() + "'.";
    consoleLogger.info(infoMsg);
    logger.info(infoMsg);

    // create the statistics emitter mbean
    try {
      statisticsEmitterMBean = new StatisticsEmitterMBeanImpl(statistics_config, statisticsBuffer);
    } catch (NotCompliantMBeanException e) {
      throw new TCRuntimeException("Unable to construct the " + StatisticsEmitterMBeanImpl.class.getName()
                                   + " MBean; this is a programming error. Please go fix that class.", e);
    }

    // setup an empty statistics retrieval registry
    statisticsRetrievalRegistry = new StatisticsRetrievalRegistryImpl();
    try {
      statisticsManagerMBean = new StatisticsManagerMBeanImpl(statistics_config, statisticsRetrievalRegistry, statisticsBuffer);
    } catch (NotCompliantMBeanException e) {
      throw new TCRuntimeException("Unable to construct the " + StatisticsManagerMBeanImpl.class.getName()
                                   + " MBean; this is a programming error. Please go fix that class.", e);
    }

    active = true;
    return true;
  }

  public void registerMBeans(MBeanServer server) throws MBeanRegistrationException, NotCompliantMBeanException, InstanceAlreadyExistsException {
    server.registerMBean(statisticsEmitterMBean, StatisticsMBeanNames.STATISTICS_EMITTER);
    server.registerMBean(statisticsManagerMBean, StatisticsMBeanNames.STATISTICS_MANAGER);
  }

  public void unregisterMBeans(MBeanServer server) throws InstanceNotFoundException, MBeanRegistrationException {
    try {
      server.unregisterMBean(StatisticsMBeanNames.STATISTICS_EMITTER);
    } catch (InstanceNotFoundException e) {
      logger.warn("Unexpected error while unregistering mbean '" + StatisticsMBeanNames.STATISTICS_EMITTER + "'", e);
    }
    try {
      server.unregisterMBean(StatisticsMBeanNames.STATISTICS_MANAGER);
    } catch (Exception e) {
      logger.warn("Unexpected error while unregistering mbean '" + StatisticsMBeanNames.STATISTICS_MANAGER + "'", e);
    }
  }

  public void disableJMX() throws Exception {
    if (statisticsEmitterMBean != null) {
      statisticsEmitterMBean.disable();
    }
  }

  public synchronized void cleanup() throws Exception {
    if (statisticsBuffer != null) {
      statisticsBuffer.close();
    }
  }

  public StatisticsRetrievalRegistry getStatisticsRetrievalRegistry() {
    return statisticsRetrievalRegistry;
  }

  public AgentStatisticsManager getStatisticsManager() {
    return statisticsManagerMBean;
  }
}