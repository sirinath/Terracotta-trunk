/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import org.apache.commons.cli.Options;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.terracotta.license.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tc.cli.CommandLineBuilder;
import com.tc.config.schema.CommonL2Config;
import com.tc.config.schema.setup.ConfigurationSetupManagerFactory;
import com.tc.config.schema.setup.FatalIllegalConfigurationChangeHandler;
import com.tc.config.schema.setup.L2ConfigurationSetupManager;
import com.tc.config.schema.setup.StandardConfigurationSetupManagerFactory;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.object.config.schema.L2DSOConfigObject;
import com.tc.security.PwProvider;
import com.tc.util.concurrent.ThreadUtil;
import com.terracotta.management.keychain.KeyChain;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class TCStop {
  private static final TCLogger consoleLogger = CustomerLogging.getConsoleLogger();

  public static final String    DEFAULT_HOST  = "localhost";
  public static final int       DEFAULT_PORT  = 9540;
  private static final int MAX_TRIES = 50;
  private static final int TRY_INTERVAL = 1000;

  public static final void main(String[] args) throws Exception {
    Options options = StandardConfigurationSetupManagerFactory
        .createOptions(StandardConfigurationSetupManagerFactory.ConfigMode.L2);
    CommandLineBuilder commandLineBuilder = new CommandLineBuilder(TCStop.class.getName(), args);
    commandLineBuilder.setOptions(options);
    commandLineBuilder.addOption("u", "username", true, "username", String.class, false);
    commandLineBuilder.addOption("w", "password", true, "password", String.class, false);
    commandLineBuilder.addOption("s", "secured", false, "secured", String.class, false);
    commandLineBuilder.addOption("force", "force", false, "force", String.class, false);
    commandLineBuilder.addOption("h", "help", String.class, false);
    commandLineBuilder.addOption("k", "ignoreSSLCert", false, "Ignore untrusted SSL certificate", String.class, false);

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

    if (commandLineBuilder.hasOption('k')) {
      // disable SSL certificate verification

      System.setProperty("tc.ssl.trustAllCerts", "true");
      System.setProperty("tc.ssl.disableHostnameVerifier", "true");

      TrustManager[] trustAllCerts = new TrustManager[] {new X509TrustManager() {
        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
          return null;
        }
        @Override
        public void checkClientTrusted(X509Certificate[] certs, String authType) {
        }
        @Override
        public void checkServerTrusted(X509Certificate[] certs, String authType) {
        }
      }
      };

      SSLContext sc = SSLContext.getInstance("TLS");
      sc.init(null, trustAllCerts, null);
      HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

      HostnameVerifier allHostsValid = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
          return true;
        }
      };

      HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
    }

    String defaultName = StandardConfigurationSetupManagerFactory.DEFAULT_CONFIG_SPEC;
    File configFile = new File(System.getProperty("user.dir"), defaultName);
    boolean configSpecified = commandLineBuilder.hasOption('f');
    boolean nameSpecified = commandLineBuilder.hasOption('n');
    boolean userNameSpecified = commandLineBuilder.hasOption('u');
    boolean passwordSpecified = commandLineBuilder.hasOption('w');
    boolean forceSpecified = commandLineBuilder.hasOption("force");
    boolean securedSpecified = commandLineBuilder.hasOption("s");
    ArrayList tmpArgs = new ArrayList(Arrays.asList(args));
    tmpArgs.remove("-force");
    tmpArgs.remove("--force");

    if (forceSpecified) {
      consoleLogger.info("A forced shutdown is requested");
    }

    String userName = null;
    final String password;
    if (userNameSpecified) {
      userName = commandLineBuilder.getOptionValue('u');
      if (passwordSpecified) {
        password = commandLineBuilder.getOptionValue('w');
      } else {
        final Console console = System.console();
        if (console != null) {
          password = new String(console.readPassword("Enter password: ")); // Hu?!
        } else {
          password = CommandLineBuilder.readPassword();
        }
      }
    } else {
      password = null;
    }

    boolean secured = securedSpecified;
    if (configSpecified || System.getProperty("tc.config") != null || configFile.exists()) {
      if (!configSpecified && System.getProperty("tc.config") == null) {

        tmpArgs.add("-f");
        final String absolutePath = configFile.getAbsolutePath();
        if (securedSpecified && absolutePath.indexOf('@') == -1 && userNameSpecified) {
          tmpArgs.add(userName + "@" + absolutePath);
        } else {
          tmpArgs.add(absolutePath);
        }
      }

      args = (String[]) tmpArgs.toArray(new String[tmpArgs.size()]);

      FatalIllegalConfigurationChangeHandler changeHandler = new FatalIllegalConfigurationChangeHandler();
      ConfigurationSetupManagerFactory factory = new StandardConfigurationSetupManagerFactory(
                                                                                              args,
                                                                                              StandardConfigurationSetupManagerFactory.ConfigMode.L2,
                                                                                              changeHandler,
                                                                                              new PwProvider() {
                                                                                                @Override
                                                                                                public char[] getPasswordFor(final URI uri) {
                                                                                                  return getPassword();
                                                                                                }

                                                                                                @Override
                                                                                                @SuppressWarnings("hiding")
                                                                                                public char[] getPasswordForTC(final String user,
                                                                                                                               final String host,
                                                                                                                               final int port) {
                                                                                                  return getPassword();
                                                                                                }

                                                                                                private char[] getPassword() {
                                                                                                  return password != null ? password
                                                                                                      .toCharArray()
                                                                                                      : null;
                                                                                                }
                                                                                              });

      String name = null;
      if (nameSpecified) {
        name = commandLineBuilder.getOptionValue('n');
      }

      L2ConfigurationSetupManager manager = factory.createL2TVSConfigurationSetupManager(name, false);
      String[] servers = manager.allCurrentlyKnownServers();

      if (manager.isSecure() || securedSpecified) {
        // Create a security manager that will set the default SSL context
        final Class<?> securityManagerClass = Class.forName("com.tc.net.core.security.TCClientSecurityManager");
        securityManagerClass.getConstructor(KeyChain.class, boolean.class).newInstance(null, true);
        secured = true;
      }

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

      CommonL2Config serverConfig = manager.commonL2ConfigFor(name);

      host = serverConfig.managementPort().getBind();
      if (host == null || host.equals("0.0.0.0")) host = serverConfig.host();
      if (host == null) host = name;
      if (host == null) host = DEFAULT_HOST;
      port = computeManagementPort(serverConfig);
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
      restStop(host, port, userName, password, forceSpecified, secured);
    } catch (SecurityException se) {
      consoleLogger.error(se.getMessage(), se);
      commandLineBuilder.usageAndDie();
    } catch (Exception e) {
      Throwable root = getRootCause(e);
      if (root instanceof ConnectException) {
        consoleLogger.error("Unable to connect to host '" + host + "', port " + port
                            + ". Are you sure there is a Terracotta server instance running there?");
      } else {
        consoleLogger.error("Unexpected error while stopping server", root);
      }
      System.exit(1);
    }
  }

  static int computeManagementPort(CommonL2Config l2Config) {
    if (l2Config.managementPort() != null) {
      return l2Config.managementPort().getIntValue() == 0 ? DEFAULT_PORT : l2Config.managementPort().getIntValue();
    } else {
      return L2DSOConfigObject.computeManagementPortFromTSAPort(l2Config.tsaPort().getIntValue());
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

  public static void restStop(String host, int port, String username, String password, boolean forceStop,
                              boolean secured) throws IOException {
    InputStream myInputStream = null;
    String prefix = secured ? "https" : "http";
    String urlAsString = prefix + "://" + host + ":" + port + "/tc-management-api/v2/local/shutdown";

    StringBuilder sb = new StringBuilder();
    // adding some data to send along with the request to the server
    sb.append("{\"forceStop\":\"").append(forceStop).append("\"}");
    URL url = new URL(urlAsString);
    for (int i = 0; i < MAX_TRIES; i++) {
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      OutputStreamWriter wr = null;
      int responseCode = 0;
      try {
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        String headerValue = username + ":" + password;
        byte[] bytes = headerValue.getBytes("UTF-8");
        String encodeBase64 = Base64.encodeBytes(bytes);
        // Basic auth
        conn.addRequestProperty("Authorization", "Basic " + encodeBase64);

        // we send as text/plain , the forceStop attribute, that basically is a boolean
        conn.addRequestProperty("Content-Type", "application/json");
        conn.addRequestProperty("Accept", "*/*");

        wr = new OutputStreamWriter(conn.getOutputStream());
        // this is were we're adding post data to the request
        wr.write(sb.toString());
        wr.flush();
        wr.close();

        responseCode = conn.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
          myInputStream = conn.getInputStream();
          consoleLogger.debug("Stopping with REST call " + urlAsString + ", response code is " + responseCode);
          consoleLogger.debug("REST response: " + IOUtils.toString(myInputStream));
          break;
        } else {
          if (responseCode == 401) {
            consoleLogger.error("Authentication failure. Invalid username/password.");
            i = MAX_TRIES; // abort immediately
          } else if (conn.getErrorStream() != null) {
            String content = IOUtils.toString(conn.getErrorStream());
            consoleLogger.debug("Error response: " + content);

            String error = content; // default case is the raw error response

            // attempt to parse error as Json object first
            try {
              ObjectMapper mapper = new ObjectMapper();
              Map<String, Object> restResponse = mapper.readValue(content, Map.class);
              error = (String) restResponse.get("error");
            } catch (Exception mapException) {
              // not a json response, ignore
              if (responseCode == 404) {
                if (i < MAX_TRIES) {
                  consoleLogger.info("Got a 404, the REST service might not yet be fully started. Waiting a bit and trying again.");
                  ThreadUtil.reallySleep(TRY_INTERVAL);
                  continue;
                }
                error = urlAsString
                        + " was not found (Response code 404). Was the server configured with management-tsa-war?";
              } else {
                error = conn.getResponseMessage() + ". Response code: " + responseCode;
              }
            }
            throw new IOException(error);
          }
        }
      } finally {
        IOUtils.closeQuietly(wr);
        IOUtils.closeQuietly(myInputStream);
        conn.disconnect();
      }
    }
  }
}
