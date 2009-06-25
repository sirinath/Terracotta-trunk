/*
 * All content copyright (c) 2003-2009 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.statistics;

import com.tc.config.schema.NewStatisticsConfig;
import com.tc.config.schema.dynamic.ConfigItemListener;
import com.tc.config.schema.dynamic.FileConfigItem;
import com.tc.properties.TCProperties;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.statistics.StatisticsAgentSubSystem;
import com.tc.statistics.StatisticsAgentSubSystemImpl;
import com.tc.statistics.StatisticsSystemType;
import java.io.File;
import junit.framework.TestCase;

public class StatisticsAgentImplSetupCompleteTest extends TestCase {

  public StatisticsAgentImplSetupCompleteTest(String testName) {
    super(testName);
  }

  public void testSetupCompleteSuccess() throws Exception {
    NewStatisticsConfig config1 = new TestStatisticsConfig("child1");

    StatisticsAgentSubSystem agent1 = new StatisticsAgentSubSystemImpl();
    agent1.setup(StatisticsSystemType.CLIENT, config1);
    agent1.waitUntilSetupComplete();
    assertTrue(agent1.isActive());

    NewStatisticsConfig config2 = new TestStatisticsConfig("child2");

    StatisticsAgentSubSystem agent2 = new StatisticsAgentSubSystemImpl();
    agent2.setup(StatisticsSystemType.CLIENT, config2);
    agent2.waitUntilSetupComplete();
    assertTrue(agent2.isActive());
  }

  public void testSetupCompleteFailure() throws Exception {
    NewStatisticsConfig config = new TestStatisticsConfig("child");

    StatisticsAgentSubSystem agent1 = new StatisticsAgentSubSystemImpl();
    agent1.setup(StatisticsSystemType.CLIENT, config);
    agent1.waitUntilSetupComplete();
    assertTrue(agent1.isActive());

    setClientFailBufferOpen(true);

    StatisticsAgentSubSystem agent2 = new StatisticsAgentSubSystemImpl();
    agent2.setup(StatisticsSystemType.CLIENT, config);
    agent2.waitUntilSetupComplete();
    assertFalse(agent2.isActive());
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    setClientFailBufferOpen(false);
  }

  private static class TestStatisticsConfig implements NewStatisticsConfig {

    private final String tmpDirChild;

    public TestStatisticsConfig(String tmpDirChild) {
      this.tmpDirChild = tmpDirChild;
    }

    public FileConfigItem statisticsPath() {
      return new FileConfigItem() {

        public void addListener(ConfigItemListener changeListener) {
          throw new UnsupportedOperationException("Not supported yet.");
        }

        public Object getObject() {
          throw new UnsupportedOperationException("Not supported yet.");
        }

        public void removeListener(ConfigItemListener changeListener) {
          throw new UnsupportedOperationException("Not supported yet.");
        }

        public File getFile() {
          return new File(System.getProperty("java.io.tmpdir"), tmpDirChild);
        }
      };
    }
  }

  private void setClientFailBufferOpen(boolean flag) {
    final String fail_buffer_open_sysprop = TCPropertiesImpl.tcSysProp(TCPropertiesConsts.CVT_CLIENT_FAIL_BUFFER_OPEN);
    TCProperties tcProps = TCPropertiesImpl.getProperties();
    tcProps.setProperty(TCPropertiesConsts.CVT_CLIENT_FAIL_BUFFER_OPEN, String.valueOf(flag));
    System.setProperty(fail_buffer_open_sysprop, String.valueOf(flag));
  }
}
