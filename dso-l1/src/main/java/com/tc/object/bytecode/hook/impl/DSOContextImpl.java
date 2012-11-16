/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.bytecode.hook.impl;

import org.apache.commons.io.CopyUtils;
import org.apache.log4j.Hierarchy;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;
import org.apache.log4j.spi.RootLogger;

import com.tc.client.AbstractClientFactory;
import com.tc.config.schema.L2ConfigForL1.L2Data;
import com.tc.config.schema.setup.ConfigurationSetupException;
import com.tc.config.schema.setup.FatalIllegalConfigurationChangeHandler;
import com.tc.config.schema.setup.L1ConfigurationSetupManager;
import com.tc.config.schema.setup.StandardConfigurationSetupManagerFactory;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.core.SecurityInfo;
import com.tc.net.core.security.TCSecurityManager;
import com.tc.object.bytecode.Manager;
import com.tc.object.bytecode.ManagerImpl;
import com.tc.object.bytecode.hook.DSOContext;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.StandardDSOClientConfigHelperImpl;
import com.tc.object.loaders.ClassProvider;
import com.tc.object.logging.RuntimeLoggerImpl;
import com.tc.util.Assert;
import com.tc.util.TCTimeoutException;
import com.tc.util.Util;
import com.tc.util.io.ServerURL;
import com.terracottatech.config.ConfigurationModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Map;

public class DSOContextImpl implements DSOContext {

  private static final TCLogger       logger = TCLogging.getLogger(DSOContextImpl.class);

  private final DSOClientConfigHelper configHelper;
  private final Manager               manager;

  private final boolean               expressRejoinClient;

  public static DSOContext createStandaloneContext(String configSpec, ClassLoader loader, boolean expressRejoinClient)
      throws ConfigurationSetupException {
    return createStandaloneContext(configSpec, loader, expressRejoinClient, null, new SecurityInfo());
  }

  public static DSOContext createStandaloneContext(String configSpec, ClassLoader loader, boolean expressRejoinClient,
                                                   TCSecurityManager securityManager, SecurityInfo securityInfo)
      throws ConfigurationSetupException {
    StandardConfigurationSetupManagerFactory factory = new StandardConfigurationSetupManagerFactory(
                                                                                                    (String[]) null,
                                                                                                    StandardConfigurationSetupManagerFactory.ConfigMode.EXPRESS_L1,
                                                                                                    new FatalIllegalConfigurationChangeHandler(),
                                                                                                    configSpec, securityManager);

    L1ConfigurationSetupManager config = factory.getL1TVSConfigurationSetupManager(securityInfo);
    config.setupLogging();
    PreparedComponentsFromL2Connection l2Connection;
    try {
      l2Connection = validateMakeL2Connection(config, securityManager);
    } catch (Exception e) {
      throw new ConfigurationSetupException(e.getLocalizedMessage(), e);
    }

    DSOClientConfigHelper configHelper = new StandardDSOClientConfigHelperImpl(config);
    RuntimeLoggerImpl runtimeLogger = new RuntimeLoggerImpl(configHelper);

    Manager manager = new ManagerImpl(true, null, null, null, null, configHelper, l2Connection, true, runtimeLogger,
                                      loader, expressRejoinClient, securityManager);

    DSOContextImpl context = createContext(configHelper, manager, expressRejoinClient);

    try {
      context.startToolkitConfigurator();
    } catch (Exception e) {
      throw new ConfigurationSetupException(e.getLocalizedMessage(), e);
    }

    manager.init();

    return context;
  }

  public static TCSecurityManager createSecurityManager(Map<String, Object> env) {
    return AbstractClientFactory.getFactory().createClientSecurityManager(env);
  }


  private void startToolkitConfigurator() throws Exception {
    Class toolkitConfiguratorClass = null;
    try {
      toolkitConfiguratorClass = Class.forName("com.terracotta.toolkit.EnterpriseToolkitConfigurator");
    } catch (ClassNotFoundException e) {
      toolkitConfiguratorClass = Class.forName("com.terracotta.toolkit.ToolkitConfigurator");
    }

    Object toolkitConfigurator = toolkitConfiguratorClass.newInstance();
    Method start = toolkitConfiguratorClass.getMethod("start", DSOClientConfigHelper.class);
    start.invoke(toolkitConfigurator, configHelper);
  }

  private static DSOContextImpl createContext(DSOClientConfigHelper configHelper, Manager manager,
                                              boolean expressRejoinClient) {
    return new DSOContextImpl(configHelper, manager.getClassProvider(), manager, expressRejoinClient);
  }

  private DSOContextImpl(DSOClientConfigHelper configHelper, ClassProvider classProvider, Manager manager,
                         boolean expressRejoinClient) {
    Assert.assertNotNull(configHelper);

    resolveClasses();

    this.expressRejoinClient = expressRejoinClient;
    this.configHelper = configHelper;
    this.manager = manager;
    logger.info("DSOContext created with expressRejoinClient=" + expressRejoinClient);
  }

  private void resolveClasses() {
    // This is to help a deadlock in log4j (see MNK-3461, MNK-3512)
    Logger l = new RootLogger(Level.ALL);
    Hierarchy h = new Hierarchy(l);
    l.addAppender(new WriterAppender(new PatternLayout(TCLogging.FILE_AND_JMX_PATTERN), new OutputStream() {
      @Override
      public void write(int b) {
        //
      }
    }));
    l.debug(h.toString(), new Throwable());
  }

  @Override
  public Manager getManager() {
    return this.manager;
  }

  @Override
  public void addTunneledMBeanDomain(String mbeanDomain) {
    this.configHelper.addTunneledMBeanDomain(mbeanDomain);
  }

  private static PreparedComponentsFromL2Connection validateMakeL2Connection(L1ConfigurationSetupManager config, final TCSecurityManager securityManager)
      throws UnknownHostException, IOException, TCTimeoutException {
    L2Data[] l2Data = config.l2Config().l2Data();
    Assert.assertNotNull(l2Data);

    if (false && !config.loadedFromTrustedSource()) {
      String serverConfigMode = getServerConfigMode(l2Data[0].host(), l2Data[0].dsoPort(), config.getSecurityInfo());

      if (serverConfigMode != null && serverConfigMode.equals(ConfigurationModel.PRODUCTION)) {
        String text = "Configuration constraint violation: "
                      + "untrusted client configuration not allowed against production server";
        throw new AssertionError(text);
      }
    }

    return new PreparedComponentsFromL2Connection(config, securityManager);
  }

  private static final long MAX_HTTP_FETCH_TIME       = 30 * 1000; // 30 seconds
  private static final long HTTP_FETCH_RETRY_INTERVAL = 1 * 1000; // 1 second

  private static String getServerConfigMode(String serverHost, int httpPort, SecurityInfo securityInfo)
      throws TCTimeoutException, IOException {

    ServerURL theURL = new ServerURL(serverHost, httpPort, "/config?query=mode", securityInfo);

    long startTime = System.currentTimeMillis();
    long lastTrial = 0;

    boolean interrupted = false;
    try {
      while (System.currentTimeMillis() < (startTime + MAX_HTTP_FETCH_TIME)) {
        try {
          long untilNextTrial = HTTP_FETCH_RETRY_INTERVAL - (System.currentTimeMillis() - lastTrial);

          if (untilNextTrial > 0) {
            try {
              Thread.sleep(untilNextTrial);
            } catch (InterruptedException ie) {
              interrupted = true;
            }
          }

          logger.debug("Opening connection to: " + theURL + " to fetch server configuration.");

          lastTrial = System.currentTimeMillis();
          InputStream in = theURL.openStream();
          logger.debug("Got input stream to: " + theURL);
          ByteArrayOutputStream baos = new ByteArrayOutputStream();

          CopyUtils.copy(in, baos);

          return baos.toString();
        } catch (ConnectException ce) {
          logger.warn("Unable to fetch configuration mode from L2 at '" + theURL + "'; trying again. "
                      + "(Is an L2 running at that address?): " + ce.getLocalizedMessage());
          // oops -- try again
        }
      }
    } finally {
      Util.selfInterruptIfNeeded(interrupted);
    }

    throw new TCTimeoutException("We tried for " + (int) ((System.currentTimeMillis() - startTime) / 1000)
                                 + " seconds, but couldn't fetch system configuration mode from the L2 " + "at '"
                                 + theURL + "'. Is the L2 running?");
  }

  @Override
  public void shutdown() {
    if (expressRejoinClient) {
      manager.stopImmediate();
    } else {
      manager.stop();
    }
  }
}
