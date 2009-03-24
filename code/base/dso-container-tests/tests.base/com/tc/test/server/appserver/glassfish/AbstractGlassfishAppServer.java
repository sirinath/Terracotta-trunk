/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.glassfish;

import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;
import com.tc.lcp.CargoLinkedChildProcess;
import com.tc.lcp.HeartBeatService;
import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.TestConfigObject;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AbstractAppServer;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.test.server.appserver.deployment.WARBuilder;
import com.tc.test.server.util.AppServerUtil;
import com.tc.text.Banner;
import com.tc.util.Assert;
import com.tc.util.Grep;
import com.tc.util.PortChooser;
import com.tc.util.concurrent.ThreadUtil;
import com.tc.util.runtime.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Glassfish AppServer implementation
 */
public abstract class AbstractGlassfishAppServer extends AbstractAppServer {

  private static final int    STARTUP_RETRIES    = 3;

  private static final String JAVA_CMD           = System.getProperty("java.home") + File.separator + "bin"
                                                   + File.separator + "java";

  private static final String ADMIN_USER         = "admin";
  private static final String PASSWD             = "password";
  private static final String PINGWAR            = "ping";

  private static final long   START_STOP_TIMEOUT = 1000 * 300;
  private final PortChooser   pc                 = new PortChooser();
  private final int           httpPort           = pc.chooseRandomPort();
  private final int           adminPort          = pc.chooseRandomPort();
  private File                passwdFile;
  private Thread              runner;
  private File                instanceDir;

  public AbstractGlassfishAppServer(GlassfishAppServerInstallation installation) {
    super(installation);
  }

  private synchronized File getPasswdFile() throws IOException {
    if (passwdFile == null) {
      passwdFile = File.createTempFile("passwd", "");
      passwdFile.deleteOnExit();

      FileOutputStream fos = null;
      try {
        fos = new FileOutputStream(passwdFile);
        fos.write(("AS_ADMIN_ADMINPASSWORD=" + PASSWD).getBytes());
        fos.write("\n".getBytes());
        fos.write(("AS_ADMIN_MASTERPASSWORD=" + PASSWD).getBytes());
        fos.write("\n".getBytes());
      } finally {
        if (fos != null) {
          try {
            fos.close();
          } catch (IOException ioe) {
            // ignore
          }
        }
      }
    }

    return passwdFile;
  }

  private static String getPlatformScript(String name) {
    if (Os.isWindows()) { return name + ".bat"; }
    return name;
  }

  private void createDomain(AppServerParameters params) throws Exception {
    File asAdminScript = getAsadminScript();

    List cmd = new ArrayList();
    cmd.add(asAdminScript.getAbsolutePath());
    cmd.add("create-domain");
    cmd.add("--interactive=false");
    cmd.add("--domaindir=" + sandboxDirectory());
    cmd.add("--adminport");
    cmd.add(String.valueOf(adminPort));
    cmd.add("--adminuser");
    cmd.add(ADMIN_USER);
    cmd.add("--passwordfile");
    cmd.add(getPasswdFile().getAbsolutePath());
    cmd.add("--instanceport");
    cmd.add(String.valueOf(httpPort));
    cmd.add("--savemasterpassword=true");
    cmd.add("--domainproperties");
    cmd.add("jms.port=" + pc.chooseRandomPort() + ":" + "orb.listener.port=" + pc.chooseRandomPort() + ":"
            + "http.ssl.port=" + pc.chooseRandomPort() + ":" + "orb.ssl.port=" + pc.chooseRandomPort() + ":"
            + "orb.mutualauth.port=" + pc.chooseRandomPort() + ":" + "domain.jmxPort=" + pc.chooseRandomPort());
    cmd.add("--savelogin=true");
    cmd.add(params.instanceName());

    Result result = Exec.execute((String[]) cmd.toArray(new String[] {}), null, null, asAdminScript.getParentFile());

    if (result.getExitCode() != 0) { throw new RuntimeException(result.toString()); }
  }

  private static void checkFile(File file) {
    if (!file.isFile()) { throw new RuntimeException(file.getAbsolutePath() + " is not a file or does not exist"); }
    if (!file.canRead()) { throw new RuntimeException(file.getAbsolutePath() + " cannot be read"); }
  }

  private File getInstanceFile(String path) {
    Assert.assertNotNull(instanceDir);
    File f = new File(instanceDir, path);
    checkFile(f);
    return f;
  }

  private File getAsadminScript() {
    File glassfishInstall = this.serverInstallDirectory();
    File asAdminScript = new File(new File(glassfishInstall, "bin"), getPlatformScript("asadmin"));
    checkFile(asAdminScript);
    return asAdminScript;
  }

  public ServerResult start(ServerParameters rawParams) throws Exception {
    AppServerParameters params = (AppServerParameters) rawParams;
    for (int i = 0; i < STARTUP_RETRIES; i++) {
      try {
        return start0(new ParamsWithRetry(params, i));
      } catch (RetryException re) {
        Banner.warnBanner("Re-trying server startup (" + i + ") " + re.getMessage());
        continue;
      }
    }

    throw new RuntimeException("Failed to start server in " + STARTUP_RETRIES + " attempts");
  }

  private ServerResult start0(AppServerParameters params) throws Exception {
    instanceDir = createInstance(params);

    instanceDir.delete(); // createDomain will fail if directory already exists
    if (instanceDir.exists()) { throw new RuntimeException("Instance dir must not exist: "
                                                           + instanceDir.getAbsolutePath()); }

    createDomain(params);

    modifyDomainConfig(params);

    setProperties(params, httpPort, instanceDir);

    final String cmd[] = getStartupCommand(params);
    final File nodeLogFile = new File(instanceDir.getParent(), instanceDir.getName() + ".log");
    final Process process = Runtime.getRuntime().exec(cmd, null, instanceDir);

    runner = new Thread("runner for " + params.instanceName()) {
      @Override
      public void run() {
        try {
          Result result = Exec.execute(process, cmd, nodeLogFile.getAbsolutePath(), startupInput(), instanceDir);
          if (result.getExitCode() != 0) {
            System.err.println(result);
          }
        } catch (Throwable e) {
          e.printStackTrace();
        }
      }
    };
    runner.start();
    System.err.println("Starting " + params.instanceName() + " on port " + httpPort + "...");

    boolean started = false;
    long timeout = System.currentTimeMillis() + START_STOP_TIMEOUT;
    while (System.currentTimeMillis() < timeout) {
      if (AppServerUtil.pingPort(adminPort)) {
        started = true;
        break;
      }

      if (!runner.isAlive()) {
        if (amxDebugCheck(nodeLogFile)) { throw new RetryException("NPE in AMXDebug"); }
        throw new RuntimeException("Runner thread finished before timeout");
      }
    }

    if (!started) { throw new RuntimeException("Failed to start server in " + START_STOP_TIMEOUT + "ms"); }

    System.err.println("Started " + params.instanceName() + " on port " + httpPort);

    waitForAppInstanceRunning(params);

    deployWars(process, nodeLogFile, params.wars());

    waitForPing();

    return new AppServerResult(httpPort, this);
  }

  private static boolean amxDebugCheck(File nodeLogFile) throws IOException {
    // see DEV-1722
    List<CharSequence> hits = Grep.grep("^Caused by: java.lang.NullPointerException$", nodeLogFile);
    List<CharSequence> hits2 = Grep.grep("^\tat com.sun.appserv.management.base.AMXDebug.getDebug", nodeLogFile);

    return (!hits.isEmpty() && !hits2.isEmpty());
  }

  private void waitForPing() {
    String pingUrl = "http://localhost:" + httpPort + "/ping/index.html";
    WebConversation wc = new WebConversation();
    wc.setExceptionsThrownOnErrorStatus(false);
    int tries = 10;
    for (int i = 0; i < tries; i++) {
      WebResponse response;
      try {
        System.err.println("Pinging " + pingUrl + " - try #" + i);
        response = wc.getResponse(pingUrl);
        if (response.getResponseCode() == 200) break;
      } catch (Exception e) {
        // ignored
      }
      ThreadUtil.reallySleep(2000);
    }
  }

  private void waitForAppInstanceRunning(final AppServerParameters params) throws Exception {
    while (true) {
      String status = getAppInstanceStatus(params);
      System.err.println(params.instanceName() + " is " + status);
      if ("running".equals(status)) {
        break;
      }
      System.err.println("Sleeping for 2 sec before checking again...");
      ThreadUtil.reallySleep(2000);
    }
  }

  private String getAppInstanceStatus(AppServerParameters params) throws Exception {
    File asAdminScript = getAsadminScript();
    List cmd = new ArrayList();
    cmd.add(asAdminScript.getAbsolutePath());
    cmd.add("list-domains");
    cmd.add("--domaindir=" + sandboxDirectory());

    Result result = Exec.execute((String[]) cmd.toArray(new String[] {}), null, null, asAdminScript.getParentFile());

    /**
     * Output should be something like this: <instance_name> <status> where <instance_name> is the name of the instance
     * <status> is one of {not running, running, starting}
     */
    // System.err.println("list-domains output: \n" + result.getStdout());
    if (result.getStderr().trim().length() > 0) {
      System.err.println("Error Stream: " + result.getStderr());
    }
    System.err.flush();

    if (result.getExitCode() != 0) { throw new RuntimeException(result.toString()); }

    return getStatus(params.instanceName(), result.getStdout());
  }

  private String getStatus(final String instanceName, final String output) {
    int start = output.indexOf(instanceName);
    if (start < 0) { return ""; }
    String line = output.substring(start);

    int end = line.indexOf("\n");
    if (end < 0) { throw new RuntimeException("no end: " + line); }
    line = line.substring(0, end).trim();

    final int delim = line.indexOf(" ");
    String appName = line.substring(0, delim);
    String status = line.substring(delim + 1);
    Assert.assertEquals(appName, instanceName);

    return status.trim();
  }

  private static byte[] startupInput() {
    try {
      String eol = System.getProperty("line.separator");

      final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      bytes.write((ADMIN_USER + eol + PASSWD + eol + PASSWD + eol).getBytes());
      if (Os.isWindows()) {
        bytes.write((byte) 26); // ctrl-Z
      } else {
        bytes.write((byte) 4); // ctrl-D
      }

      return bytes.toByteArray();
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  private void deployWars(Process process, File nodeLogFile, Map wars) throws Exception {
    for (Iterator iter = wars.entrySet().iterator(); iter.hasNext();) {
      Map.Entry entry = (Entry) iter.next();
      String warName = (String) entry.getKey();
      File warFile = (File) entry.getValue();
      deployWar(warName, warFile, process, nodeLogFile);
    }

    // deploy the ping app so we can test to see
    // if wars are ready
    deployWar(PINGWAR, createPingWarFile(PINGWAR + ".war"), process, nodeLogFile);
  }

  private File createPingWarFile(String warName) throws Exception {
    DeploymentBuilder builder = new WARBuilder(warName, new File(sandboxDirectory(), "war"));
    builder.addResourceFullpath("/com/tc/test/server/appserver/glassfish", "index.html", "index.html");
    return builder.makeDeployment().getFileSystemPath().getFile();
  }

  private void deployWar(String warName, File warFile, Process process, File nodeLogFile) throws IOException, Exception {
    System.err.println("Deploying war [" + warName + "] on " + instanceDir.getName());

    List cmd = new ArrayList();
    cmd.add(getAsadminScript().getAbsolutePath());
    cmd.add("deploy");
    cmd.add("--interactive=false");
    cmd.add("--user");
    cmd.add(ADMIN_USER);
    cmd.add("--passwordfile");
    cmd.add(getPasswdFile().getAbsolutePath());
    cmd.add("--contextroot=" + warName);
    cmd.add("--port=" + adminPort);
    cmd.add(warFile.getAbsolutePath());

    Result result = Exec.execute((String[]) cmd.toArray(new String[] {}));

    if (result.getExitCode() == 0) {
      System.err.println("Deployed war file successfully.");
      return;
    }

    // deploy failed. Stop the process and see if it the known "web1" problem
    process.destroy();
    ThreadUtil.reallySleep(3000);
    List<CharSequence> hits = Grep
        .grep("^SEVERE: WEB0610: WebModule \\[/web1\\] failed to deploy and has been disabled$", nodeLogFile);
    if (!hits.isEmpty()) { throw new RetryException(result.toString()); }

    // Generic deploy failure
    throw new RuntimeException("Deploy failed for " + warName + ": " + result);
  }

  abstract protected String[] getDisplayCommand(String script);

  private String[] getStartupCommand(AppServerParameters params) throws Exception {
    File startScript = getInstanceFile("bin/" + getPlatformScript("startserv"));

    Result result = Exec.execute(getDisplayCommand(startScript.getAbsolutePath()), null, null, startScript
        .getParentFile());
    if (result.getExitCode() != 0) { throw new RuntimeException("error executing startserv script: " + result); }

    String output = result.getStdout().trim();

    if (!output.startsWith("STARTOFCOMMAND|") || !output.endsWith("|ENDOFCOMMAND|")) { throw new RuntimeException(
                                                                                                                  "cannot parse output: "
                                                                                                                      + output); }

    output = output.substring("STARTOFCOMMAND|".length());
    output = output.substring(0, output.length() - "|ENDOFCOMMAND|".length());

    List cmd = new ArrayList(Arrays.asList(output.split("\\|")));

    modifyStartupCommand(cmd);

    // add the linked java process stuff to classpath
    for (int i = 0; i < cmd.size(); i++) {
      String s = (String) cmd.get(i);

      if (s.toLowerCase().trim().equals("-classpath") || s.toLowerCase().trim().equals("-cp")) {
        // the classpath is set with java.class.path system property, check these just for good measure
        throw new RuntimeException("unexpected classpath arguments in startup command " + cmd);
      }

      if (s.startsWith("-Djava.class.path=")) {
        cmd.set(i, s + File.pathSeparator + TestConfigObject.getInstance().extraClassPathForAppServer());
        break;
      }
    }

    String mainArg = (String) cmd.remove(cmd.size() - 1);
    String mainClass = (String) cmd.remove(cmd.size() - 1);

    if (!"com.sun.enterprise.server.PELaunch".equals(mainClass)) { throw new RuntimeException("Unexpected main class: "
                                                                                              + mainClass); }
    if (!"start".equals(mainArg)) { throw new RuntimeException("unexpected main argument: " + mainArg); }

    cmd.add(CargoLinkedChildProcess.class.getName());
    cmd.add(mainClass);
    cmd.add(String.valueOf(HeartBeatService.listenPort()));
    cmd.add(instanceDir.toString());
    cmd.add(mainArg);

    cmd.add(0, JAVA_CMD);
    return (String[]) cmd.toArray(new String[] {});
  }

  protected void modifyStartupCommand(List cmd) {
    //
  }

  private void modifyDomainConfig(AppServerParameters params) throws Exception {
    File domainXML = getInstanceFile("config/domain.xml");

    System.err.println("Modifying domain configuration at " + domainXML.getAbsolutePath());

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    // disable the resolve of the external DTD -- The monkey failed once since it timed out talking to sun's web site
    factory.setAttribute("http://apache.org/xml/features/nonvalidating/load-external-dtd", Boolean.FALSE);

    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document = builder.parse(domainXML);

    NodeList list = document.getElementsByTagName("java-config");

    if (list.getLength() != 1) { throw new RuntimeException("wrong number of elements " + list.getLength()); }

    Node javaConfig = list.item(0);

    // if you want debugging of the spawned glassfish, play with this
    if (false) {
      NamedNodeMap attrs = javaConfig.getAttributes();
      attrs.getNamedItem("debug-enabled").setNodeValue("true");
      attrs.getNamedItem("debug-options")
          .setNodeValue("-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000");
    }

    appendDSOParams(document, javaConfig, params);

    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();

    if (document.getDoctype() != null) {
      transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, document.getDoctype().getPublicId());
      transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, document.getDoctype().getSystemId());
    }

    StringWriter sw = new StringWriter();
    transformer.transform(new DOMSource(document), new StreamResult(sw));

    FileUtils.writeStringToFile(domainXML, sw.toString(), "UTF-8");
  }

  private void appendDSOParams(Document doc, Node node, AppServerParameters params) {
    String[] jvmArgs = params.jvmArgs().replaceAll("'", "").split("\\s");

    for (String arg : jvmArgs) {
      Element element = doc.createElement("jvm-options");
      element.appendChild(doc.createTextNode(arg));
      node.appendChild(element);
    }

    // workaround for DSO early initialization of NIO stuff
    // XXX: when/if this can be magically worked around, this option can removed
    Element element = doc.createElement("jvm-options");
    element.appendChild(doc.createTextNode("-Dcom.sun.enterprise.server.ss.ASQuickStartup=false"));
    node.appendChild(element);
  }

  public void stop() throws Exception {
    System.err.println("Stopping instance on port " + httpPort + "...");

    File stopScript = getInstanceFile("bin/" + getPlatformScript("stopserv"));
    Result result = Exec.execute(new String[] { stopScript.getAbsolutePath() }, null, null, stopScript.getParentFile());
    if (result.getExitCode() != 0) {
      System.err.println(result);
    }

    if (runner != null) {
      runner.join(START_STOP_TIMEOUT);
      if (runner.isAlive()) {
        Banner.errorBanner("instance still running on port " + httpPort);
      } else {
        System.err.println("Stopped instance on port " + httpPort);
      }
    }

  }

  private static class RetryException extends Exception {
    RetryException(String msg) {
      super(msg);
    }
  }

  private static class ParamsWithRetry implements AppServerParameters {

    private final AppServerParameters delegate;
    private final int                 retryNum;

    ParamsWithRetry(AppServerParameters delegate, int retryNum) {
      this.delegate = delegate;
      this.retryNum = retryNum;
    }

    public String classpath() {
      return delegate.classpath();
    }

    public String instanceName() {
      return delegate.instanceName() + (retryNum == 0 ? "" : "-retry" + retryNum);
    }

    public String jvmArgs() {
      return delegate.jvmArgs();
    }

    public Properties properties() {
      return delegate.properties();
    }

    public Collection sars() {
      return delegate.sars();
    }

    public Map wars() {
      return delegate.wars();
    }

  }

}
