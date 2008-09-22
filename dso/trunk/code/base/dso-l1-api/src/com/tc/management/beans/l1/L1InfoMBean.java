/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.management.beans.l1;

import com.tc.management.RuntimeStatisticConstants;
import com.tc.management.TerracottaMBean;
import com.tc.statistics.StatisticData;

import java.util.Map;

import javax.management.NotificationEmitter;

public interface L1InfoMBean extends TerracottaMBean, NotificationEmitter, RuntimeStatisticConstants {

  String getVersion();

  String getBuildID();
  
  boolean isPatched();
  
  String getPatchLevel();
  
  String getPatchVersion();
  
  String getPatchBuildID();
  
  String getCopyright();
  
  String takeThreadDump(long requestMillis);

  void startBeanShell(int port);

  String getEnvironment();

  String getConfig();

  String[] getCpuStatNames();

  Map getStatistics();

  StatisticData[] getCpuUsage();

}
