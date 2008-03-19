/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.statistics.retrieval.actions;

import com.tc.statistics.StatisticType;
import com.tc.statistics.retrieval.actions.SRACacheObjectsEvicted;
import com.tc.util.Assert;
import com.tc.test.TCTestCase;

public class SRACacheObjectsEvictedTest extends TCTestCase {

  public void testCacheObjectsEvicted() {
    SRACacheObjectsEvicted sra = new SRACacheObjectsEvicted();
    Assert.assertEquals(sra.getName(), SRACacheObjectsEvicted.ACTION_NAME);
    Assert.assertEquals(sra.getType(), StatisticType.TRIGGERED);

    try {
      sra.retrieveStatisticData();
      fail("SRADistributedGC cannot be used to collect statistics data");
    } catch (UnsupportedOperationException e) {
      //ok
    }
  }
}
