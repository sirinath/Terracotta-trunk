/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.management.beans.l1;

import com.tc.management.RuntimeStatisticConstants;
import com.tc.management.TerracottaMBean;

import java.util.Map;

import javax.management.NotificationEmitter;

public interface L1InfoMBean extends TerracottaMBean, NotificationEmitter, RuntimeStatisticConstants {
  String takeThreadDump(long requestMillis);

  String getEnvironment();

  String getConfig();

  String[] getCpuStatNames();
  
  Map getStatistics();
}
