/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest;


public class ShadowVariableTest extends TransparentTestBase {

  private static final int NODE_COUNT = 2;

  public ShadowVariableTest() {
    this.disableAllUntil("2009-08-01");
  }
  
  public void setUp() throws Exception {
    super.setUp();
    getTransparentAppConfig().setClientCount(NODE_COUNT).setIntensity(1);
    initializeTestRunner();
  }

  protected Class getApplicationClass() {
    return ShadowVariableTestApp.class;
  }
}
