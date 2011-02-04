/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.storage.derby;

import com.tc.config.schema.setup.L2ConfigurationSetupManager;
import com.tc.management.beans.object.ServerDBBackupMBean;
import com.tc.objectserver.storage.api.DBEnvironment;
import com.tc.objectserver.storage.api.DBFactory;
import com.tc.stats.counter.sampled.SampledCounter;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

public class DerbyDBFactory implements DBFactory {
  private final Properties properties;

  public DerbyDBFactory(final Properties properties) {
    this.properties = properties;
  }

  public DBEnvironment createEnvironment(boolean paranoid, File envHome, SampledCounter l2FaultFromDisk)
      throws IOException {
    return new DerbyDBEnvironment(paranoid, envHome, properties, l2FaultFromDisk);
  }

  public ServerDBBackupMBean getServerDBBackupMBean(L2ConfigurationSetupManager configurationSetupManager) {
    // TODO
    return null;
  }
}
