/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest;

public class ShadowRootTest extends TransparentTestBase {

  private static final int NODE_COUNT = 1;

  public ShadowRootTest() {
    this.disableAllUntil("2009-08-01");    
  }
  
  public void setUp() throws Exception {
    super.setUp();
    getTransparentAppConfig().setClientCount(NODE_COUNT).setIntensity(1);
    initializeTestRunner();
  }

  protected Class getApplicationClass() {
    return ShadowRootTestApp.class;
  }
}
