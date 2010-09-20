/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.storage.berkeleydb;

import com.tc.config.schema.setup.L2TVSConfigurationSetupManager;
import com.tc.management.beans.object.ServerDBBackup;
import com.tc.management.beans.object.ServerDBBackupMBean;
import com.tc.objectserver.storage.api.DBEnvironment;
import com.tc.objectserver.storage.api.DBFactory;
import com.tc.stats.counter.sampled.SampledCounter;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import javax.management.NotCompliantMBeanException;

public class BerkeleyDBFactory implements DBFactory {
  private final Properties properties;

  public BerkeleyDBFactory(final Properties properties) {
    this.properties = properties;
  }

  public DBEnvironment createEnvironment(boolean paranoid, File envHome, SampledCounter l2FaultFromDisk)
      throws IOException {
    return new BerkeleyDBEnvironment(paranoid, envHome, properties, l2FaultFromDisk);
  }

  public ServerDBBackupMBean getServerDBBackupMBean(final L2TVSConfigurationSetupManager configurationSetupManager)
      throws NotCompliantMBeanException {
    return new ServerDBBackup(configurationSetupManager);
  }
}
