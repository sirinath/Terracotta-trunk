package com.tc.test.server.appserver.was6x;

import org.apache.commons.io.IOUtils;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.TestConfigObject;
import com.tc.test.server.Server;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AbstractAppServer;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.util.AppServerUtil;
import com.tc.util.PortChooser;
import com.tc.util.runtime.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Was6xAppServer extends AbstractAppServer {

  private static final TCLogger logger                     = TCLogging.getLogger(Server.class);

  private static final String   TERRACOTTA_PY              = "terracotta.py";
  private static final String   DEPLOY_APPS_PY             = "deployApps.py";
  private static final String   ENABLE_DSO_PY              = "enable-dso.py";
  private static final String   DSO_JVMARGS                = "__DSO_JVMARGS__";
  private static final String   PORTS_DEF                  = "ports.def";
  private static final int      START_STOP_TIMEOUT_SECONDS = 5 * 60;

  private String[]              scripts                    = new String[] { DEPLOY_APPS_PY, TERRACOTTA_PY,
      ENABLE_DSO_PY                                       };

  private String                policy                     = "grant codeBase \"file:FILENAME\" {"
                                                             + IOUtils.LINE_SEPARATOR
                                                             + "  permission java.security.AllPermission;"
                                                             + IOUtils.LINE_SEPARATOR + "};" + IOUtils.LINE_SEPARATOR;
  private String                instanceName;
  private String                dsoJvmArgs;
  private int                   webspherePort;
  private File                  sandbox;
  private File                  instanceDir;
  private File                  pyScriptsDir;
  private File                  dataDir;
  private File                  warDir;
  private File                  portDefFile;
  private File                  serverInstallDir;
  private File extraScript;

  private Thread                serverThread;

  public Was6xAppServer(Was6xAppServerInstallation installation) {
    super(installation);
  }

  public ServerResult start(ServerParameters parameters) throws Exception {
    init(parameters);
    createPortFile();
    copyPythonScripts();
    patchTerracottaPy();
    deleteProfileIfExists();
    createProfile();
    verifyProfile();
    deployWarFile();
    addTerracottaToServerPolicy();
    enableDSO();
    if (extraScript != null) {
      executeJythonScript(extraScript);
    }
    serverThread = new Thread() {
      public void run() {
        try {
          startWebsphere();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    serverThread.setDaemon(true);
    serverThread.start();
    AppServerUtil.waitForPort(webspherePort, START_STOP_TIMEOUT_SECONDS * 1000);
    logger.info("Websphere instance " + instanceName + " started on port " + webspherePort);
    return new AppServerResult(webspherePort, this);
  }

  public void stop() throws Exception {
    try {
      stopWebsphere();
    } catch (Exception e) {
      // ignored
    } finally {
      try {
        deleteProfile();
      } catch (Exception e2) {
        // ignored
      }
    }
  }

  private void createPortFile() throws Exception {
    PortChooser portChooser = new PortChooser();
    webspherePort = portChooser.chooseRandomPort();

    List lines = IOUtils.readLines(getClass().getResourceAsStream(PORTS_DEF));
    lines.set(0, (String) lines.get(0) + webspherePort);

    for (int i = 1; i < lines.size(); i++) {
      String line = (String) lines.get(i);
      lines.set(i, line + portChooser.chooseRandomPort());
    }
    if (logger.isDebugEnabled()) {
      logger.debug("createPortFile() using ports: " + lines);
    }

    writeLines(lines, portDefFile, false);
  }

  private void copyPythonScripts() throws Exception {
    for (int i = 0; i < scripts.length; i++) {
      logger.debug("copyPythonScripts(): copying file[" + scripts[i] + "] to directory [" + pyScriptsDir + "]");
      copyResourceTo(scripts[i], new File(pyScriptsDir, scripts[i]));
    }
  }

  private void patchTerracottaPy() throws FileNotFoundException, IOException, Exception {
    File terracotta_py = new File(pyScriptsDir, TERRACOTTA_PY);
    FileInputStream fin = new FileInputStream(terracotta_py);
    List lines = IOUtils.readLines(fin);
    fin.close();

    // replace __DSO_JVMARGS__
    for (int i = 0; i < lines.size(); i++) {
      String line = (String) lines.get(i);
      if (line.indexOf(DSO_JVMARGS) >= 0) {
        if (logger.isDebugEnabled()) {
          logger.debug("patchTerracottaPy(): patching line: " + line);
        }
        line = line.replaceFirst(DSO_JVMARGS, dsoJvmArgs);
        if (logger.isDebugEnabled()) {
          logger.debug("patchTerracottaPy(): after patching line: " + line);
        }
        lines.set(i, line);
      }
    }

    writeLines(lines, terracotta_py, false);
  }

  private void enableDSO() throws Exception {
    String[] args = new String[] { "-lang", "jython", "-connType", "NONE", "-profileName", instanceName, "-f",
        new File(pyScriptsDir, ENABLE_DSO_PY).getAbsolutePath() };
    executeCommand(serverInstallDir, "wsadmin", args, pyScriptsDir, "Error in enabling DSO for " + instanceName);
  }

  private void executeJythonScript(File script) throws Exception {
    String[] args = new String[] { "-lang", "jython", "-connType", "NONE", "-profileName", instanceName, "-f",
        script.getAbsolutePath() };
    executeCommand(serverInstallDir, "wsadmin", args, pyScriptsDir, "Error executing " + script);
  }

  private void deleteProfile() throws Exception {
    String[] args = new String[] { "-delete", "-profileName", instanceName };
    executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "Error in deleting profile for "
                                                                               + instanceName);
  }

  private void createProfile() throws Exception {
    String defaultTemplate = new File(serverInstallDir.getAbsolutePath(), "profileTemplates/default").getAbsolutePath();
    String[] args = new String[] { "-create", "-templatePath", defaultTemplate, "-profileName", instanceName,
        "-profilePath", instanceDir.getAbsolutePath(), "-portsFile", portDefFile.getAbsolutePath(),
        "-enableAdminSecurity", "false", "-isDeveloperServer" };
    logger.info("Creating profile for instance " + instanceName + "...");
    long start = System.currentTimeMillis();
    executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "Error in creating profile for "
                                                                               + instanceName);
    long elapsedMillis = System.currentTimeMillis() - start;
    long elapsedSeconds = elapsedMillis / 1000;
    Long elapsedMinutes = new Long(elapsedSeconds / 60);
    logger.info("Profile creation time: "
                + MessageFormat.format("{0,number,##}:{1}.{2}", new Object[] { elapsedMinutes,
                    new Long(elapsedSeconds % 60), new Long(elapsedMillis % 1000) }));
  }

  private void verifyProfile() throws Exception {
    if (!(instanceDir.exists() && instanceDir.isDirectory())) {
      Exception e = new Exception("Unable to verify profile for instance '" + instanceName + "'");
      logger.error("WebSphere profile '" + instanceName + "' does not exist at " + instanceDir.getAbsolutePath(), e);
      throw e;
    }
    logger.info("WebSphere profile '" + instanceName + "' is verified at " + instanceDir.getAbsolutePath());
  }

  private void deleteProfileIfExists() throws Exception {
    // call "manageprofiles.sh -validateAndUpdateRegistry" to clean out corrupted profiles
    String[] args = new String[] { "-validateAndUpdateRegistry" };
    executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "");
    args = new String[] { "-listProfiles" };
    String output = executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "");
    if (output.indexOf(instanceName) >= 0) {
      args = new String[] { "-delete", "-profileName", instanceName };
      executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "Trying to clean up existing profile");
    }
  }

  private void addTerracottaToServerPolicy() throws Exception {
    String classpath = System.getProperty("java.class.path");
    Set set = new HashSet();
    String[] entries = classpath.split(File.pathSeparator);
    for (int i = 0; i < entries.length; i++) {
      File filename = new File(entries[i]);
      if (filename.isDirectory()) {
        set.add(filename);
      } else {
        set.add(filename.getParentFile());
      }
    }

    List lines = new ArrayList(set.size() + 1);
    for (Iterator it = set.iterator(); it.hasNext();) {
      lines.add(getPolicyFor((File) it.next()));
    }
    lines.add(getPolicyFor(new File(TestConfigObject.getInstance().normalBootJar())));

    writeLines(lines, new File(new File(instanceDir, "properties"), "server.policy"), true);
  }

  private String getPolicyFor(File filename) {
    String entry = filename.getAbsolutePath().replace('\\', '/');

    if (filename.isDirectory()) {
      return policy.replaceFirst("FILENAME", entry + "/-");
    } else {
      return policy.replaceFirst("FILENAME", entry);
    }
  }

  private void copyResourceTo(String filename, File dest) throws Exception {
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(dest);
      IOUtils.copy(getClass().getResourceAsStream(filename), fos);
    } finally {
      IOUtils.closeQuietly(fos);
    }
  }

  private void deployWarFile() throws Exception {
    String[] args = new String[] { "-lang", "jython", "-connType", "NONE", "-profileName", instanceName, "-f",
        new File(pyScriptsDir, DEPLOY_APPS_PY).getAbsolutePath(), warDir.getAbsolutePath().replace('\\', '/') };
    logger.info("Deploying war file in: " + warDir);
    executeCommand(serverInstallDir, "wsadmin", args, pyScriptsDir, "Error in deploying warfile for " + instanceName);
    logger.info("Done deploying war file in: " + warDir);
  }

  private void startWebsphere() throws Exception {
    String[] args = new String[] { "server1", "-profileName", instanceName, "-trace", "-timeout",
        String.valueOf(START_STOP_TIMEOUT_SECONDS) };
    executeCommand(serverInstallDir, "startServer", args, instanceDir, "Error in starting " + instanceName);
  }

  private void stopWebsphere() throws Exception {
    String[] args = new String[] { "server1", "-profileName", instanceName };
    executeCommand(serverInstallDir, "stopServer", args, instanceDir, "Error in stopping " + instanceName);
    if (serverThread != null) {
      serverThread.join(START_STOP_TIMEOUT_SECONDS * 1000);
    }
  }

  private void init(ServerParameters parameters) {
    AppServerParameters params = (AppServerParameters) parameters;
    sandbox = sandboxDirectory();
    instanceName = params.instanceName();
    instanceDir = new File(sandbox, instanceName);
    dataDir = new File(sandbox, "data");
    warDir = new File(sandbox, "war");
    pyScriptsDir = new File(dataDir, instanceName);
    pyScriptsDir.mkdirs();
    portDefFile = new File(pyScriptsDir, PORTS_DEF);
    serverInstallDir = serverInstallDirectory();

    String[] jvm_args = params.jvmArgs().replaceAll("'", "").replace('\\', '/').split("\\s+");
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < jvm_args.length; i++) {
      sb.append("\"" + jvm_args[i] + "\"");
      if (i < jvm_args.length - 1) {
        sb.append(", ");
      }
    }
    dsoJvmArgs = sb.toString();

    if (logger.isDebugEnabled()) {
      logger.debug("init{sandbox}          ==> " + sandbox.getAbsolutePath());
      logger.debug("init{instanceName}     ==> " + instanceName);
      logger.debug("init{instanceDir}      ==> " + instanceDir.getAbsolutePath());
      logger.debug("init{webappDir}        ==> " + dataDir.getAbsolutePath());
      logger.debug("init{pyScriptsDir}     ==> " + pyScriptsDir.getAbsolutePath());
      logger.debug("init{portDefFile}      ==> " + portDefFile.getAbsolutePath());
      logger.debug("init{serverInstallDir} ==> " + serverInstallDir.getAbsolutePath());
      logger.debug("init{dsoJvmArgs}       ==> " + dsoJvmArgs);
    }
  }

  private String getScriptPath(File root, String scriptName) {
    File bindir = new File(root, "bin");
    return new File(bindir, (Os.isWindows() ? scriptName + ".bat" : scriptName + ".sh")).getAbsolutePath();
  }

  private String executeCommand(File rootDir, String scriptName, String[] args, File workingDir, String errorMessage)
      throws Exception {
    String script = getScriptPath(rootDir, scriptName);
    String[] cmd = new String[args.length + 1];
    cmd[0] = script;
    System.arraycopy(args, 0, cmd, 1, args.length);
    logger.info("Executing cmd: " + Arrays.asList(cmd));
    Result result = Exec.execute(cmd, null, null, workingDir == null ? instanceDir : workingDir);
    final StringBuffer stdout = new StringBuffer(result.getStdout());
    final StringBuffer stderr = new StringBuffer(result.getStderr());
    if (logger.isDebugEnabled()) {
      logger.debug("STDOUT for[" + Arrays.asList(cmd) + "]:\n" + stdout);
      logger.debug("STDERR for[" + Arrays.asList(cmd) + "]:\n" + stderr);
    }
    if (result.getExitCode() != 0) {
      logger.warn("Command did not return 0; message is: " + errorMessage);
    }
    return stdout.append(IOUtils.LINE_SEPARATOR).append(stderr).toString();
  }

  private void writeLines(List lines, File filename, boolean append) throws Exception {
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(filename, append);
      IOUtils.writeLines(lines, IOUtils.LINE_SEPARATOR, fos);
    } finally {
      IOUtils.closeQuietly(fos);
    }
  }

  public void setExtraScript(File extraScript) {
    this.extraScript = extraScript;
  }
}
