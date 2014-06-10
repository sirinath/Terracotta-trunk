/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.server.util;

import org.apache.commons.io.FileUtils;
import org.apache.xmlbeans.XmlException;
import org.terracotta.license.util.Base64;

import com.tc.cli.CommandLineBuilder;
import com.tc.config.Loader;
import com.tc.config.schema.dynamic.ParameterSubstituter;
import com.tc.object.config.schema.L2DSOConfigObject;
import com.terracottatech.config.Server;
import com.terracottatech.config.Servers;
import com.terracottatech.config.TcConfigDocument;
import com.terracottatech.config.TcConfigDocument.TcConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ServerStat {
  private static final String   UNKNOWN          = "unknown";
  private static final String   NEWLINE          = System.getProperty("line.separator");
  static final int            DEFAULT_MANAGEMENT_PORT = 9520;

  private final String          host;
  private final String          hostName;
  private final int             port;
  private final String        username;
  private final String        password;
  private final boolean       secured;

  private boolean               connected;
  static String                 groupName        = "UNKNOWN";
  static String                 errorMessage     = "";
  static String                 state;
  static String                 role;
  static String                 health;

  public ServerStat(String username, String password, boolean secured, String host, String hostAlias, int port) {
    this.username = username;
    this.password = password;
    this.secured = secured;
    this.host = host;
    this.hostName = hostAlias;
    this.port = port;
  }

  public String getState() {
   return state;
  }

  public String getRole() {
    return role;
  }

  public String getHealth() {
    return health;
  }

  /**
   * Finds and returns the name of the group which this server belongs to.
   */
  public String getGroupName() {
    if (!connected) return UNKNOWN;
    return groupName;
  }


  @Override
  public String toString() {
    String serverId = hostName != null ? hostName : host;
    StringBuilder sb = new StringBuilder();
    sb.append(serverId + ".health: " + getHealth()).append(NEWLINE);
    sb.append(serverId + ".role: " + getRole()).append(NEWLINE);
    sb.append(serverId + ".state: " + getState()).append(NEWLINE);
    sb.append(serverId + ".jmxport: " + port).append(NEWLINE);
    sb.append(serverId + ".group name: " + getGroupName()).append(NEWLINE);
    if (!connected) {
      sb.append(serverId + ".error: " + errorMessage).append(NEWLINE);
    }
    return sb.toString();
  }


  public static void main(String[] args) throws Exception {
    String usage = " server-stat -s host1,host2" + NEWLINE + "       server-stat -s host1:9520,host2:9520" + NEWLINE
        + "       server-stat -f /path/to/tc-config.xml" + NEWLINE;

    CommandLineBuilder commandLineBuilder = new CommandLineBuilder(ServerStat.class.getName(), args);

    commandLineBuilder.addOption("s", true, "Terracotta server instance list (comma separated)", String.class, false,
        "list");
    commandLineBuilder.addOption("f", true, "Terracotta tc-config file", String.class, false, "file");
    commandLineBuilder.addOption("h", "help", String.class, false);
    commandLineBuilder.addOption(null, "secured", false, "secured", String.class, false);
    commandLineBuilder.addOption("u", "username", true, "username", String.class, false);
    commandLineBuilder.addOption("w", "password", true, "password", String.class, false);
    commandLineBuilder.setUsageMessage(usage);
    commandLineBuilder.parse();

    if (commandLineBuilder.hasOption('h')) {
      commandLineBuilder.usageAndDie();
    }

    boolean secured = false;
    if (commandLineBuilder.hasOption("secured")) {
      initSecurityManager();
      secured = true;
    }

    String username = null;
    String password = null;
    if (commandLineBuilder.hasOption('u')) {
      username = commandLineBuilder.getOptionValue('u');
      if (commandLineBuilder.hasOption('w')) {
        password = commandLineBuilder.getOptionValue('w');
      } else {
        password = CommandLineBuilder.readPassword();
      }
    }

    String hostList = commandLineBuilder.getOptionValue('s');
    String configFile = commandLineBuilder.getOptionValue('f');

    try {
      if (configFile != null) {
        handleConfigFile(username, password, secured, configFile);
      } else {
        handleList(username, password, secured, hostList);
      }
    } catch (Exception e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  private static void initSecurityManager() throws Exception {
    final Class<?> securityManagerClass = Class.forName("com.tc.net.core.security.TCClientSecurityManager");
    securityManagerClass.getConstructor(boolean.class).newInstance(true);
  }

  private static void handleConfigFile(String username, String password, boolean secured, String configFilePath) throws Exception {
    TcConfigDocument tcConfigDocument = null;
    try {

      String configFileContent = FileUtils.readFileToString(new File(configFilePath));
      String configFileSubstitutedContent = ParameterSubstituter.substitute(configFileContent);

      tcConfigDocument = new Loader().parse(configFileSubstitutedContent);
    } catch (IOException e) {
      throw new RuntimeException("Error reading " + configFilePath + ": " + e.getMessage());
    } catch (XmlException e) {
      throw new RuntimeException("Error parsing " + configFilePath + ": " + e.getMessage());
    }
    TcConfig tcConfig = tcConfigDocument.getTcConfig();
    Servers tcConfigServers = tcConfig.getServers();
    Server[] servers = L2DSOConfigObject.getServers(tcConfigServers);
    for (Server server : servers) {
      String host = server.getHost();
      String hostName = server.getName();
      int jmxPort = computeJMXPort(server);
      if (!secured && tcConfigServers.isSetSecure() && tcConfigServers.getSecure()) {
        initSecurityManager();
        secured = true;
      }
      ServerStat stat = new ServerStat(username, password, secured, host, hostName, jmxPort);
      System.out.println(stat.toString());
    }
  }

  static int computeJMXPort(Server server) {
    if (server.isSetJmxPort()) {
      return server.getJmxPort().getIntValue() == 0 ? DEFAULT_MANAGEMENT_PORT : server.getJmxPort().getIntValue();
    } else {
      return L2DSOConfigObject.computeJMXPortFromTSAPort(server.getTsaPort().getIntValue());
    }
  }

  private static void handleList(String username, String password, boolean secured, String hostList) {
    if (hostList == null) {
      printStat(username, password, secured, "localhost:9520");
    } else {
      String[] pairs = hostList.split(",");
      for (String info : pairs) {
        printStat(username, password, secured, info);
        System.out.println();
      }
    }
  }

  // info = host | host:port
  private static void printStat(String username, String password, boolean secured, String info) {
    String host = info;
    int port = DEFAULT_MANAGEMENT_PORT;
    if (info.indexOf(':') > 0) {
      String[] args = info.split(":");
      host = args[0];
      try {
        port = Integer.valueOf(args[1]);
      } catch (NumberFormatException e) {
        throw new RuntimeException("Failed to parse jmxport: " + info);
      }
    }
    
    InputStream myInputStream = null;
    String prefix = secured ? "https" : "http";
    String urlAsString = prefix + "://" + host + ":" + port + "/tc-management-api/v2/local/stat";

    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlAsString);
      conn = (HttpURLConnection) url.openConnection();
      conn.setDoOutput(true);
      conn.setRequestMethod("GET");
      String headerValue = username + ":" + password;
      byte[] bytes = headerValue.getBytes("UTF-8");
      String encodeBase64 = Base64.encodeBytes(bytes);
      // Basic auth
      conn.addRequestProperty("Basic", encodeBase64);

      // we send as text/plain , the forceStop attribute, that basically is a boolean
      conn.addRequestProperty("Content-Type", "application/json");
      conn.addRequestProperty("Accept", "*/*");

      myInputStream = conn.getInputStream();
    } catch (IOException e) {
      java.util.Scanner s = new java.util.Scanner(conn.getErrorStream()).useDelimiter("\\A");
      errorMessage = "Unexpected error while getting stat: " + e.getMessage();
    } finally {
      conn.disconnect();
    }
    if (myInputStream != null) {
      java.util.Scanner s = new java.util.Scanner(myInputStream).useDelimiter("\\A");
      String responseContent = s.hasNext() ? s.next() : "";
      // { "health" : "OK", "role" : "ACTIVE", "state": "ACTIVE-COORDINATOR", "managementPort" : "9540", "serverGroupName" : "defaultGroup"}
      decodeJsonAndSetFields(responseContent);
      // consoleLogger.debug("Response code is : " + responseCode);
      // consoleLogger.debug("Response content is : " + responseContent);
    }
    
    ServerStat stat = new ServerStat(username, password, secured, host, null, port);
    System.out.println(stat.toString());
  }

  static void decodeJsonAndSetFields(String responseContent) {
    String strippedResponseContent = responseContent.replace("{", "");
    strippedResponseContent = strippedResponseContent.replace("}", "");
    String[] splittedFields = strippedResponseContent.split(",");
    for (String jsonKeyValue : splittedFields) {
      String[] keyValue = jsonKeyValue.split(":");
      String key = keyValue[0].trim();
      key = key.replace("\"","");
      String value = keyValue[1].trim();
      value = value.replace("\"","");
      
      if("health" .equals(key)) {
        health =  value;
      }
      if("role" .equals(key)) {
        role =  value;
      }
      if("state" .equals(key)) {
        state =  value;
      }
      if("managementPort" .equals(key)) {
        // port = Integer.valueOf(value);
      }
      if("serverGroupName" .equals(key)) {
        groupName = value;
      }
    }
  }
}
