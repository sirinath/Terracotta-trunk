/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest;

import com.tc.util.runtime.Vm;

import java.util.Date;

public class GenericQueueTest extends TransparentTestBase {
  private static final int NODE_COUNT = 3;

  public GenericQueueTest() {
    if (Vm.isIBM()) {
      // these currently don't have to work on the IBM JDK
      disableAllUntil(new Date(Long.MAX_VALUE));
    }
  }
  
  protected void setUp() throws Exception {
    super.setUp();
    getTransparentAppConfig().setClientCount(NODE_COUNT);
    initializeTestRunner();
  }

  protected Class getApplicationClass() {
    return GenericQueueTestApp.class;
  }

}
