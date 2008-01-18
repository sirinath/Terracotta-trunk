/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import com.tc.object.config.ConfigVisitor;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tc.util.TIMUtil;

public class Ehcache124JMXTest extends EhcacheJMXTestBase {

  protected Class getApplicationClass() {
    return App.class;
  }

  public static class App extends BaseApp {

    public App(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
      super(appId, cfg, listenerProvider);
    }

    public static void visitL1DSOConfig(final ConfigVisitor visitor, final DSOClientConfigHelper config) {
      config.addModule(TIMUtil.EHCACHE_1_2_4, TIMUtil.getVersion(TIMUtil.EHCACHE_1_2_4));
    }
  }

}
