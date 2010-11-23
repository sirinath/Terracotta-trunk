/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.config.schema.setup;

import com.tc.config.schema.L2ConfigForL1;
import com.tc.config.schema.NewCommonL1Config;
import com.tc.object.config.schema.NewDSOApplicationConfig;
import com.tc.object.config.schema.NewL1DSOConfig;

/**
 * Knows how to set up configuration for L1.
 */
public interface L1ConfigurationSetupManager {

  boolean loadedFromTrustedSource();

  String rawConfigText();

  NewCommonL1Config commonL1Config();

  L2ConfigForL1 l2Config();

  NewL1DSOConfig dsoL1Config();

  void setupLogging();

  String[] applicationNames();

  NewDSOApplicationConfig dsoApplicationConfigFor(String applicationName);

  void reloadServersConfiguration() throws ConfigurationSetupException;
}
