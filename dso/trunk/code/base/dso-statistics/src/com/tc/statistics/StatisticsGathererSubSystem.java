/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics;

import com.tc.config.schema.NewStatisticsConfig;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.statistics.gatherer.StatisticsGatherer;
import com.tc.statistics.gatherer.impl.StatisticsGathererImpl;
import com.tc.statistics.store.StatisticsStore;
import com.tc.statistics.store.exceptions.StatisticsStoreException;
import com.tc.statistics.store.h2.H2StatisticsStoreImpl;

import java.io.File;

public class StatisticsGathererSubSystem {
  private final static TCLogger logger        = CustomerLogging.getDSOGenericLogger();
  private final static TCLogger consoleLogger = CustomerLogging.getConsoleLogger();

  private volatile StatisticsStore statisticsStore;
  private volatile StatisticsGatherer statisticsGatherer;

  private volatile boolean active = false;

  public boolean isActive() {
    return active;
  }

  public synchronized boolean setup(final NewStatisticsConfig config) {
    // create the statistics store
    File stat_path = config.statisticsPath().getFile();
    try {
      stat_path.mkdirs();
    } catch (Exception e) {
      // TODO: needs to be properly written and put in a properties file
      String msg =
        "\n**************************************************************************************\n"
        + "Unable to create the directory '" + stat_path.getAbsolutePath() + "' for the statistics store.\n"
        + "The CVT gathering system will not be active on this node.\n"
        + "**************************************************************************************\n";
      consoleLogger.error(msg);
      logger.error(msg, e);
      return false;
    }
    try {
      statisticsStore = new H2StatisticsStoreImpl(stat_path);
      statisticsStore.open();
    } catch (StatisticsStoreException e) {
      // TODO: needs to be properly written and put in a properties file
      String msg =
        "\n**************************************************************************************\n"
        + "The statistics store couldn't be opened at \n"
        + "'" + stat_path.getAbsolutePath() + "'.\n"
        + "The CVT gathering system will not be active for this node.\n"
        + "\n"
        + "A common reason for this is that you're launching several Terracotta L1\n"
        + "clients on the same machine. The default directory for the statistics store\n"
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
    String info_msg = "Statistics store: '" + stat_path.getAbsolutePath() + "'.";
    consoleLogger.info(info_msg);
    logger.info(info_msg);

    statisticsGatherer = new StatisticsGathererImpl(statisticsStore);

    active = true;
    return true;
  }

  public synchronized void reinitialize() throws Exception {
    statisticsGatherer.reinitialize();
    statisticsStore.reinitialize();
  }

  public synchronized void cleanup() throws Exception {
    if (statisticsGatherer != null) {
      statisticsGatherer.disconnect();
    }
    if (statisticsStore != null) {
      statisticsStore.close();
    }
  }

  public StatisticsStore getStatisticsStore() {
    return statisticsStore;
  }

  public StatisticsGatherer getStatisticsGatherer() {
    return statisticsGatherer;
  }
}