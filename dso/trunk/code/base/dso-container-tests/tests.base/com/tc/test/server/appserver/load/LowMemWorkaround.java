/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.load;

import org.hyperic.sigar.Mem;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;

import com.tc.statistics.retrieval.SigarUtil;
import com.tc.test.AppServerInfo;
import com.tc.text.Banner;

public class LowMemWorkaround {
  // This number isn't exact of course but it is appropriate for our monkey environments
  private static final long TWO_GIGABYTES = 2000000000L;

  public static int computeNumberOfNodes(int defaultNum, int lowMemNum, AppServerInfo appServerInfo) {
    try {
      Sigar sigar = SigarUtil.newSigar();

      Mem mem = sigar.getMem();

      long memTotal = mem.getTotal();
      if (memTotal < TWO_GIGABYTES) {
        Banner.warnBanner("Using " + lowMemNum + " nodes (instead of " + defaultNum
                          + ") since this machine has limited memory (" + memTotal + ")");
        return lowMemNum;
      }

      return defaultNum;
    } catch (SigarException se) {
      throw new RuntimeException(se);
    }
  }
}
