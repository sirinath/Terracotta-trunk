/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import com.tc.config.schema.setup.TestTVSConfigurationSetupManagerFactory;
import com.terracottatech.config.PersistenceMode;

/**
 * This test was written specifically to expose a dead lock in sleepcat in persistence map
 */
public class MapClearDeadLocksSleepycatTest extends TransparentTestBase {

  private static final int NODE_COUNT    = 2;
  private static final int THREADS_COUNT = 2;

  public void doSetUp(TransparentTestIface t) throws Exception {
    t.getTransparentAppConfig().setClientCount(NODE_COUNT).setApplicationInstancePerClientCount(THREADS_COUNT)
        .setIntensity(1);
    t.initializeTestRunner();
  }

  protected Class getApplicationClass() {
    return MapClearDeadLocksSleepycatTestApp.class;
  }
  
  protected void setupConfig(TestTVSConfigurationSetupManagerFactory configFactory) {
    configFactory.setPersistenceMode(PersistenceMode.PERMANENT_STORE);
  }

}
