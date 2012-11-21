package org.terracotta.tests.base;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ClassUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.terracotta.test.util.TestBaseUtil;

import com.tc.l2.L2DebugLogging.LogLevel;
import com.tc.logging.TCLogging;
import com.tc.test.TCTestCase;
import com.tc.test.TestConfigObject;
import com.tc.test.config.model.TestConfig;
import com.tc.test.jmx.TestHandler;
import com.tc.test.jmx.TestHandlerMBean;
import com.tc.test.runner.TcTestRunner;
import com.tc.test.runner.TcTestRunner.Configs;
import com.tc.test.setup.GroupsData;
import com.tc.test.setup.TestJMXServerManager;
import com.tc.test.setup.TestServerManager;
import com.tc.util.PortChooser;
import com.tc.util.runtime.Vm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.MBeanServerConnection;

@RunWith(value = TcTestRunner.class)
public abstract class AbstractTestBase extends TCTestCase {
  private static final String         DEFAULT_CONFIG       = "default-config";
  protected static final String       SEP                  = File.pathSeparator;
  private final TestConfig            testConfig;
  private final File                  tcConfigFile;
  protected TestServerManager         testServerManager;
  protected final File                tempDir;
  protected File                      javaHome;
  private TestClientManager           clientRunner;
  protected TestJMXServerManager      jmxServerManager;
  private Thread                      duringRunningClusterThread;
  private volatile Thread             testExecutionThread;
  private static final String         log4jPrefix          = "log4j.logger.";
  private final Map<String, LogLevel> tcLoggingConfigs     = new HashMap<String, LogLevel>();
  private final AtomicReference<Throwable> testException    = new AtomicReference<Throwable>();

  public AbstractTestBase(TestConfig testConfig) {
    this.testConfig = testConfig;
    try {
      this.tempDir = getTempDirectory();
      tempDir.mkdir();
      FileUtils.cleanDirectory(tempDir);
      tcConfigFile = getTempFile("tc-config.xml");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    if (Vm.isJRockit()) {
      testConfig.getClientConfig().addExtraClientJvmArg("-XXfullSystemGC");
    }
    testConfig.getClientConfig().addExtraClientJvmArg("-XX:+HeapDumpOnOutOfMemoryError");
    if (Boolean.getBoolean("com.tc.test.toolkit.devmode")) {
      testConfig.getClientConfig().addExtraClientJvmArg("-Dcom.tc.test.toolkit.devmode=true");
    }
  }

  /**
   * Returns the list of testconfigs the test has to run with Overwrite this method to run the same test with multiple
   * configs
   */
  @Configs
  public static List<TestConfig> getTestConfigs() {
    TestConfig testConfig = new TestConfig(DEFAULT_CONFIG);
    testConfig.getGroupConfig().setMemberCount(1);
    TestConfig[] testConfigs = new TestConfig[] { testConfig };
    return Arrays.asList(testConfigs);
  }

  @Override
  @Before
  public void setUp() throws Exception {
    if (System.getProperty("com.tc.productkey.path") != null) {
      if (!testConfig.getL2Config().isOffHeapEnabled()) {
        System.out.println("============= Offheap is turned off, switching it on to avoid OOMEs! ==============");
        testConfig.getL2Config().setOffHeapEnabled(true);
        testConfig.getL2Config().setDirectMemorySize(1024);
        testConfig.getL2Config().setMaxOffHeapDataSize(512);
      }
    } else {
      if (testConfig.getL2Config().getRestartable()) {
        System.out.println("============== Disabling opensource restartable tests ===============");
        disableTest();
      }
    }

    tcTestCaseSetup();

    if (testWillRun) {
      try {
        System.out.println("*************** Starting Test with Test Profile : " + testConfig.getConfigName()
                           + " **************************");
        setJavaHome();
        clientRunner = new TestClientManager(tempDir, this, this.testConfig);
        if (!testConfig.isStandAloneTest()) {
          testServerManager = new TestServerManager(this.testConfig, this.tempDir, this.tcConfigFile, this.javaHome, new FailTestCallback());
          startServers();
        }
        TestHandlerMBean testHandlerMBean = new TestHandler(testServerManager, testConfig);
        jmxServerManager = new TestJMXServerManager(new PortChooser().chooseRandomPort(), testHandlerMBean);
        jmxServerManager.startJMXServer();
        executeDuringRunningCluster();
      } catch (Throwable e) {
        e.printStackTrace();
        throw new AssertionError(e);
      }
    }
  }

  protected void startServers() throws Exception {
    testServerManager.startAllServers();
  }

  protected void setJavaHome() {
    if (javaHome == null) {
      String javaHome_local = getTestConfigObject().getL2StartupJavaHome();
      if (javaHome_local == null) { throw new IllegalStateException(TestConfigObject.L2_STARTUP_JAVA_HOME
                                                                    + " must be set to a valid JAVA_HOME"); }
      javaHome = new File(javaHome_local);
    }
  }

  protected TestConfigObject getTestConfigObject() {
    return TestConfigObject.getInstance();
  }

  @Override
  @Test
  final public void runTest() throws Throwable {
    if (!testWillRun) return;

    testExecutionThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          startClients();
          postClientVerification();
        } catch (Throwable throwable) {
          testException.compareAndSet(null, throwable);
        }
      }
    }, "Test execution thread");
    testExecutionThread.setDaemon(true);
    testExecutionThread.start();
    testExecutionThread.join();

    tcTestCaseTearDown(testException.get());
  }

  private class FailTestCallback implements TestFailureListener {
    @Override
    public void testFailed(String reason) {
      if (testExecutionThread != null) {
        doDumpServerDetails();
        testException.compareAndSet(null, new Throwable(reason));
        testExecutionThread.interrupt();
      }
    }
  }

  /**
   * @return the port number on which the TestHandler Mbean can be connected
   */
  public int getTestControlMbeanPort() {
    return this.jmxServerManager.getJmxServerPort();
  }

  /**
   * returns the testConfig with which this test is running
   * 
   * @return : the test config with which the test is running
   */
  protected TestConfig getTestConfig() {
    return this.testConfig;
  }

  protected abstract String createClassPath(Class client) throws IOException;

  protected void evaluateClientOutput(String clientName, int exitCode, File output) throws Throwable {
    if ((exitCode != 0)) { throw new AssertionError("Client " + clientName + " exited with exit code: " + exitCode); }

    FileReader fr = null;
    try {
      fr = new FileReader(output);
      BufferedReader reader = new BufferedReader(fr);
      String st = "";
      while ((st = reader.readLine()) != null) {
        if (st.contains("[PASS: " + clientName + "]")) return;
      }
      throw new AssertionError("Client " + clientName + " did not pass");
    } catch (Exception e) {
      throw new AssertionError(e);
    } finally {
      try {
        fr.close();
      } catch (Exception e) {
        //
      }
    }
  }

  protected void preStart(File workDir) {
    //
  }

  /**
   * Override this method if there is a need to do some verification when the clients are done
   */
  protected void postClientVerification() throws Exception {
    //
  }

  protected String getTestDependencies() {
    return "";
  }

  protected String makeClasspath(String... jars) {
    String cp = "";
    for (String jar : jars) {
      cp += SEP + jar;
    }

    if (!tcLoggingConfigs.isEmpty()) {
      cp += SEP + getTCLoggingFilePath();
    }
    return cp;
  }

  private String getTCLoggingFilePath() {
    File log4jPropFile = null;
    String path = "";
    BufferedWriter writer = null;
    try {
      log4jPropFile = new File(getTempDirectory(), TCLogging.LOG4J_PROPERTIES_FILENAME);
      writer = new BufferedWriter(new FileWriter(log4jPropFile));
      for (Entry<String, LogLevel> entry : tcLoggingConfigs.entrySet()) {
        writer.write(log4jPrefix + entry.getKey() + "=" + entry.getValue().name() + "\n");
      }
    } catch (IOException e) {
      throw new IllegalStateException(e.getMessage());
    } finally {
      try {
        writer.close();
        path = log4jPropFile.getCanonicalPath();
      } catch (IOException e1) {
        throw new IllegalStateException(e1.getMessage());
      }
    }
    return path;
  }

  protected String addToClasspath(String cp, String path) {
    return cp + SEP + path;
  }

  protected List<String> getExtraJars() {
    return Collections.emptyList();
  }

  protected void configureTCLogging(String className, LogLevel LogLevel) {
    tcLoggingConfigs.put(className, LogLevel);
  }

  protected String getTerracottaURL() {
    return TestBaseUtil.getTerracottaURL(getGroupsData());
  }

  @Override
  @After
  public void tearDown() throws Exception {
    if (testWillRun) {
      System.out.println("Waiting for During Cluster running thread to finish");
      duringRunningClusterThread.join();
      if (!testConfig.isStandAloneTest()) this.testServerManager.stopAllServers();
      this.jmxServerManager.stopJmxServer();
      System.out.println("*************** Stopped Test with Test Profile : " + testConfig.getConfigName()
                         + " **************************");
    }
  }

  @Override
  protected File getTempDirectory() throws IOException {
    // this is a hack but there is no direct way to know whether a test is going to be run with single config
    if (testConfig.getConfigName().equals(DEFAULT_CONFIG)) { return super.getTempDirectory(); }

    File tempDirectory = new File(super.getTempDirectory(), testConfig.getConfigName());
    return tempDirectory;
  }

  @Override
  protected File getTempFile(String fileName) throws IOException {
    return new File(getTempDirectory(), fileName);
  }

  @Override
  protected boolean cleanTempDir() {
    return false;
  }

  @Override
  protected void doDumpServerDetails() {
    testServerManager.dumpClusterState(getThreadDumpCount(), getThreadDumpInterval());
  }

  protected void startClients() throws Throwable {
    int index = 0;
    Runner[] runners = testConfig.getClientConfig().isParallelClients() ? new Runner[testConfig.getClientConfig()
        .getClientClasses().length] : new Runner[] {};
    for (Class<? extends Runnable> c : testConfig.getClientConfig().getClientClasses()) {
      if (!testConfig.getClientConfig().isParallelClients()) {
        runClient(c);
      } else {
        Runner runner = new Runner(c);
        runners[index++] = runner;
        runner.start();
      }
    }

    for (Runner runner : runners) {
      runner.finish();
    }
  }

  protected void runClient(Class client) throws Throwable {
    List<String> emptyList = Collections.emptyList();
    runClient(client, client.getSimpleName(), emptyList);
  }

  protected void runClient(Class client, String clientName, List<String> extraClientArgs) throws Throwable {
    clientRunner.runClient(client, clientName, extraClientArgs);
  }

  public GroupsData getGroupData(final int groupIndex) {
    return this.testServerManager.getGroupData(groupIndex);
  }

  public GroupsData[] getGroupsData() {
    return this.testServerManager.getGroupsData();
  }

  protected void duringRunningCluster() throws Exception {
    // do not delete this method, it is used by tests that override it
  }

  public File makeTmpDir(Class klass) {
    File tmp_dir_root = new File(getTestConfigObject().tempDirectoryRoot());
    File tmp_dir = new File(tmp_dir_root, ClassUtils.getShortClassName(klass));
    tmp_dir.mkdirs();
    return tmp_dir;
  }

  private void executeDuringRunningCluster() {
    duringRunningClusterThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          duringRunningCluster();
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
      }
    });
    duringRunningClusterThread.setName(getClass().getName() + " duringRunningCluster");
    duringRunningClusterThread.start();
  }

  protected class Runner extends Thread {

    private final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
    private final Class                      clientClass;

    public Runner(Class clientClass) {
      this.clientClass = clientClass;
    }

    @Override
    public void run() {
      try {
        runClient(clientClass);
      } catch (Throwable t) {
        error.set(t);
      }
    }

    public void finish() throws Throwable {
      join();
      Throwable t = error.get();
      if (t != null) throw t;
    }
  }

  /**
   * Disables the test if the total physical memory on the machine is lower that the specified value
   * 
   * @param physicalMemory memory in gigs below which the test should not run on the machine
   */
  @SuppressWarnings("restriction")
  protected void disableIfMemoryLowerThan(int physicalMemory) {
    try {
      long gb = 1024 * 1024 * 1024;
      MBeanServerConnection mbsc = ManagementFactory.getPlatformMBeanServer();
      com.sun.management.OperatingSystemMXBean osMBean = ManagementFactory
          .newPlatformMXBeanProxy(mbsc, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME,
                                  com.sun.management.OperatingSystemMXBean.class);
      if (osMBean.getTotalPhysicalMemorySize() < physicalMemory * gb) {
        disableTest();
      }
    } catch (Exception e) {
      throw new AssertionError(e);
    }

  }

  public File getTcConfigFile() {
    return tcConfigFile;
  }

  protected void stopClient(final int index) {
    this.clientRunner.stopClient(index);
  }
}
