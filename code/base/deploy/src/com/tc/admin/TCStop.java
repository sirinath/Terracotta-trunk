/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import org.apache.commons.cli.Options;
import org.apache.commons.lang.ArrayUtils;

import com.tc.cli.CommandLineBuilder;
import com.tc.config.schema.NewCommonL2Config;
import com.tc.config.schema.setup.FatalIllegalConfigurationChangeHandler;
import com.tc.config.schema.setup.L2TVSConfigurationSetupManager;
import com.tc.config.schema.setup.StandardTVSConfigurationSetupManagerFactory;
import com.tc.config.schema.setup.TVSConfigurationSetupManagerFactory;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.management.TerracottaManagement;
import com.tc.management.beans.L2MBeanNames;
import com.tc.management.beans.TCServerInfoMBean;
import com.tc.util.concurrent.ThreadUtil;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;

public class TCStop {
  private static final TCLogger consoleLogger = CustomerLogging.getConsoleLogger();

  private final String          host;
  private final int             port;
  private final String          username;
  private final String          password;

  public static final String    DEFAULT_HOST  = "localhost";
  public static final int       DEFAULT_PORT  = 9520;

  public static final void main(String[] args) throws Exception {
    Options options = StandardTVSConfigurationSetupManagerFactory
        .createOptions(StandardTVSConfigurationSetupManagerFactory.ConfigMode.L2);
    CommandLineBuilder commandLineBuilder = new CommandLineBuilder(TCStop.class.getName(), args);
    commandLineBuilder.setOptions(options);
    commandLineBuilder.addOption("u", "username", true, "username", String.class, false);
    commandLineBuilder.addOption("w", "password", true, "password", String.class, false);
    commandLineBuilder.addOption("h", "help", String.class, false);

    commandLineBuilder.parse();
    String[] arguments = commandLineBuilder.getArguments();

    if (arguments.length > 2) {
      commandLineBuilder.usageAndDie();
    }

    String host = null;
    int port = -1;

    if (commandLineBuilder.hasOption('h')) {
      commandLineBuilder.usageAndDie();
    }

    String defaultName = StandardTVSConfigurationSetupManagerFactory.DEFAULT_CONFIG_SPEC;
    File configFile = new File(System.getProperty("user.dir"), defaultName);
    boolean configSpecified = commandLineBuilder.hasOption('f');
    boolean nameSpecified = commandLineBuilder.hasOption('n');
    boolean userNameSpecified = commandLineBuilder.hasOption('u');
    boolean passwordSpecified = commandLineBuilder.hasOption('w');

    String userName = null;
    String password = null;
    if (userNameSpecified) {
      userName = commandLineBuilder.getOptionValue('u');
      if (passwordSpecified) {
        password = commandLineBuilder.getOptionValue('w');
      } else {
        password = CommandLineBuilder.readPassword();
      }
    }

    if (configSpecified || System.getProperty("tc.config") != null || configFile.exists()) {
      if (!configSpecified && System.getProperty("tc.config") == null) {
        ArrayList tmpArgs = new ArrayList(Arrays.asList(args));

        tmpArgs.add("-f");
        tmpArgs.add(configFile.getAbsolutePath());
        args = (String[]) tmpArgs.toArray(new String[tmpArgs.size()]);
      }

      FatalIllegalConfigurationChangeHandler changeHandler = new FatalIllegalConfigurationChangeHandler();
      TVSConfigurationSetupManagerFactory factory = new StandardTVSConfigurationSetupManagerFactory(
                                                                                                    args,
                                                                                                    StandardTVSConfigurationSetupManagerFactory.ConfigMode.L2,
                                                                                                    changeHandler);

      String name = null;
      if (nameSpecified) {
        name = commandLineBuilder.getOptionValue('n');
      }

      L2TVSConfigurationSetupManager manager = factory.createL2TVSConfigurationSetupManager(name);
      String[] servers = manager.allCurrentlyKnownServers();

      if (nameSpecified && !Arrays.asList(servers).contains(name)) {
        consoleLogger.error("The specified configuration of the Terracotta server instance '" + name
                            + "' does not exist; exiting.");
        System.exit(1);
      }

      if (name == null && servers != null && servers.length == 1) {
        name = servers[0];
        consoleLogger.info("There is only one Terracotta server instance in this configuration file (" + name
                           + "); stopping it.");
      } else if (name == null && servers != null && servers.length > 1) {
        consoleLogger
            .error("There are multiple Terracotta server instances defined in this configuration file; please specify "
                   + "which one you want to stop, using the '-n' command-line option. Available servers are:\n"
                   + "    " + ArrayUtils.toString(servers));
        System.exit(1);
      }

      NewCommonL2Config serverConfig = manager.commonL2ConfigFor(name);

      host = serverConfig.host().getString();
      if (host == null) host = name;
      if (host == null) host = DEFAULT_HOST;
      port = serverConfig.jmxPort().getInt();
      consoleLogger.info("Host: " + host + ", port: " + port);
    } else {
      if (arguments.length == 0) {
        host = DEFAULT_HOST;
        port = DEFAULT_PORT;
        consoleLogger.info("No host or port provided. Stopping the Terracotta server instance at '" + host + "', port "
                           + port + " by default.");
      } else if (arguments.length == 1) {
        host = DEFAULT_HOST;
        port = Integer.parseInt(arguments[0]);
      } else {
        host = arguments[0];
        port = Integer.parseInt(arguments[1]);
      }
    }

    try {
      new TCStop(host, port, userName, password).stop();
    } catch (SecurityException se) {
      consoleLogger.error(se.getMessage());
      commandLineBuilder.usageAndDie();
    } catch (Exception e) {
      Throwable root = getRootCause(e);
      if (root instanceof ConnectException) {
        consoleLogger.error("Unable to connect to host '" + host + "', port " + port
                            + ". Are you sure there is a Terracotta server instance running there?");
      }
    }
  }

  private static Throwable getRootCause(Throwable e) {
    Throwable t = e;
    while (t != null) {
      e = t;
      t = t.getCause();
    }
    return e;
  }

  public TCStop(String host, int port) {
    this(host, port, null, null);
  }

  public TCStop(String host, int port, String userName, String password) {
    this.host = host;
    this.port = port;
    this.username = userName;
    this.password = password;
  }

  public void stop() throws IOException {
    JMXConnector jmxc = null;
    jmxc = CommandLineBuilder.getJMXConnector(username, password, host, port);
    MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();

    if (mbsc != null) {
      TCServerInfoMBean tcServerInfo = (TCServerInfoMBean) TerracottaManagement
          .findMBean(L2MBeanNames.TC_SERVER_INFO, TCServerInfoMBean.class, mbsc);
      // wait a bit for server to be ready for shutdown
      int count = 10;
      while(!tcServerInfo.isShutdownable() && --count > 0) {
        consoleLogger.warn("Waiting for server to be shutdownable...");
        ThreadUtil.reallySleep(1000);
      }
      try {
        tcServerInfo.shutdown();
      } finally {
        jmxc.close();
      }
    }
  }
}
