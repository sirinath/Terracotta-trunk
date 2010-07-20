/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.config.schema.setup;

import org.apache.xmlbeans.XmlObject;

import com.tc.config.TcProperty;
import com.tc.config.schema.ConfigTCProperties;
import com.tc.config.schema.ConfigTCPropertiesFromObject;
import com.tc.config.schema.IllegalConfigurationChangeHandler;
import com.tc.config.schema.L2ConfigForL1;
import com.tc.config.schema.L2ConfigForL1Object;
import com.tc.config.schema.NewCommonL1Config;
import com.tc.config.schema.NewCommonL1ConfigObject;
import com.tc.config.schema.defaults.DefaultValueProvider;
import com.tc.config.schema.dynamic.FileConfigItem;
import com.tc.config.schema.repository.ChildBeanFetcher;
import com.tc.config.schema.repository.ChildBeanRepository;
import com.tc.config.schema.utils.XmlObjectComparator;
import com.tc.logging.TCLogging;
import com.tc.object.config.schema.NewL1DSOConfig;
import com.tc.object.config.schema.NewL1DSOConfigObject;
import com.tc.properties.TCProperties;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;
import com.terracottatech.config.Client;
import com.terracottatech.config.DsoClientData;
import com.terracottatech.config.TcProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * The standard implementation of {@link com.tc.config.schema.setup.L1TVSConfigurationSetupManager}.
 */
public class StandardL1TVSConfigurationSetupManager extends BaseTVSConfigurationSetupManager implements
    L1TVSConfigurationSetupManager {
  private final ConfigurationCreator configurationCreator;
  private final NewCommonL1Config    commonL1Config;
  private final NewL1DSOConfig       dsoL1Config;
  private final ConfigTCProperties   configTCProperties;
  private final boolean              loadedFromTrustedSource;
  private volatile L2ConfigForL1     l2ConfigForL1;

  public StandardL1TVSConfigurationSetupManager(ConfigurationCreator configurationCreator,
                                                DefaultValueProvider defaultValueProvider,
                                                XmlObjectComparator xmlObjectComparator,
                                                IllegalConfigurationChangeHandler illegalConfigChangeHandler)
      throws ConfigurationSetupException {
    super(defaultValueProvider, xmlObjectComparator, illegalConfigChangeHandler);

    Assert.assertNotNull(configurationCreator);

    this.configurationCreator = configurationCreator;
    runConfigurationCreator(this.configurationCreator);
    loadedFromTrustedSource = this.configurationCreator.loadedFromTrustedSource();

    commonL1Config = new NewCommonL1ConfigObject(createContext(clientBeanRepository(), null));
    l2ConfigForL1 = new L2ConfigForL1Object(createContext(serversBeanRepository(), null),
                                            createContext(systemBeanRepository(), null));
    configTCProperties = new ConfigTCPropertiesFromObject((TcProperties) tcPropertiesRepository().bean());
    dsoL1Config = new NewL1DSOConfigObject(createContext(new ChildBeanRepository(clientBeanRepository(),
                                                                                 DsoClientData.class,
                                                                                 new ChildBeanFetcher() {
                                                                                   public XmlObject getChild(
                                                                                                             XmlObject parent) {
                                                                                     return ((Client) parent).getDso();
                                                                                   }
                                                                                 }), null));

    overwriteTcPropertiesFromConfig();
  }

  public void setupLogging() {
    FileConfigItem logsPath = commonL1Config().logsPath();
    TCLogging.setLogDirectory(logsPath.getFile(), TCLogging.PROCESS_TYPE_L1);
    logsPath.addListener(new LogSettingConfigItemListener(TCLogging.PROCESS_TYPE_L1));
  }

  public String rawConfigText() {
    return configurationCreator.rawConfigText();
  }

  public boolean loadedFromTrustedSource() {
    return this.loadedFromTrustedSource;
  }

  public L2ConfigForL1 l2Config() {
    return this.l2ConfigForL1;
  }

  public NewCommonL1Config commonL1Config() {
    return this.commonL1Config;
  }

  public NewL1DSOConfig dsoL1Config() {
    return this.dsoL1Config;
  }

  private void overwriteTcPropertiesFromConfig() {
    TCProperties tcProps = TCPropertiesImpl.getProperties();

    Map<String, String> propMap = new HashMap<String, String>();
    for (TcProperty tcp : this.configTCProperties.getTcPropertiesArray()) {
      propMap.put(tcp.getPropertyName(), tcp.getPropertyValue());
    }

    tcProps.overwriteTcPropertiesFromConfig(propMap);
  }

  public void reloadServersConfiguration() throws ConfigurationSetupException {
    configurationCreator.reloadServersConfiguration(serversBeanRepository(), true, false);
    // reload L2 config here as well
    L2ConfigForL1 tempL2ConfigForL1 = new L2ConfigForL1Object(createContext(serversBeanRepository(), null),
                                                              createContext(systemBeanRepository(), null));
    this.l2ConfigForL1 = tempL2ConfigForL1;
  }
}
