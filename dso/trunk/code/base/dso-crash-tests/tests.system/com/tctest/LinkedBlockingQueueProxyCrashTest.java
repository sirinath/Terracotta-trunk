/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import com.tc.test.proxyconnect.ProxyConnectManager;

public class LinkedBlockingQueueProxyCrashTest extends TransparentTestBase {

  private static final int NODE_COUNT = 4;

  public LinkedBlockingQueueProxyCrashTest() {
    //disableAllUntil("2007-06-30");
  }

  public void doSetUp(TransparentTestIface t) throws Exception {
    t.getTransparentAppConfig().setClientCount(NODE_COUNT);
    t.initializeTestRunner();
  }

  protected Class getApplicationClass() {
    return LinkedBlockingQueueCrashTestApp.class;
  }

  protected boolean canRunCrash() {
    return true;
  }

  protected boolean canRunProxyConnect() {
    return true;
  }

  protected boolean enableL1Reconnect() {
    return true;
  }

  protected void setupProxyConnectTest(ProxyConnectManager mgr) {
    mgr.setProxyWaitTime(30 * 1000);
    mgr.setProxyDownTime(100);
  }


}
