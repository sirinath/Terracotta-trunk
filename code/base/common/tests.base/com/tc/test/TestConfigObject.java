/**
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test;

import org.apache.commons.lang.StringUtils;

import com.tc.config.Directories;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.util.Assert;
import com.tc.util.runtime.Os;
import com.tc.util.runtime.Vm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

/**
 * Contains configuration data for tests.
 * </p>
 * <p>
 * This class is a singleton. This is <em>ONLY</em> because this is used all over the place, in JUnit tests.
 */
public class TestConfigObject {
  public static final String      NATIVE_LIB_LINUX_32              = "Linux";

  public static final String      NATIVE_LIB_LINUX_64              = "Linux64";

  public static final String      UNIX_NATIVE_LIB_NAME             = "libGetPid.so";

  public static final String      WINDOWS_NATIVE_LIB_NAME          = "GetPid.dll";

  public static final String      OSX_NATIVE_LIB_NAME              = "libGetPid.jnilib";

  public static final String      TC_BASE_DIR                      = "tc.base-dir";

  public static final String      SPRING_VARIANT                   = "spring";

  public static final String      WEBFLOW_VARIANT                  = "spring-webflow";

  private static final TCLogger   logger                           = TCLogging.getLogger(TestConfigObject.class);

  private static final String     OS_NAME                          = "os.name";

  private static final String     DYNAMIC_PROPERTIES_PREFIX        = "tc.tests.info.";

  private static final String     STATIC_PROPERTIES_PREFIX         = "tc.tests.configuration.";

  public static final String      PROPERTY_FILE_LIST_PROPERTY_NAME = DYNAMIC_PROPERTIES_PREFIX + "property-files";

  private static final String     TEMP_DIRECTORY_ROOT              = DYNAMIC_PROPERTIES_PREFIX + "temp-root";

  private static final String     DATA_DIRECTORY_ROOT              = DYNAMIC_PROPERTIES_PREFIX + "data-root";

  private static final String     LINKED_CHILD_PROCESS_CLASSPATH   = DYNAMIC_PROPERTIES_PREFIX
                                                                     + "linked-child-process-classpath";

  private static final String     BOOT_JAR_NORMAL                  = DYNAMIC_PROPERTIES_PREFIX + "bootjars.normal";

  private static final String     SESSION_CLASSPATH                = DYNAMIC_PROPERTIES_PREFIX + "session.classpath";

  private static final String     AVAILABLE_VARIANTS_PREFIX        = DYNAMIC_PROPERTIES_PREFIX + "variants.available.";
  private static final String     VARIANT_LIBRARIES_PREFIX         = DYNAMIC_PROPERTIES_PREFIX + "libraries.variants.";
  private static final String     SELECTED_VARIANT_PREFIX          = DYNAMIC_PROPERTIES_PREFIX + "variants.selected.";
  private static final String     DEFAULT_VARIANT_PREFIX           = STATIC_PROPERTIES_PREFIX + "variants.selected.";

  private static final String     EXECUTABLE_SEARCH_PATH           = DYNAMIC_PROPERTIES_PREFIX
                                                                     + "executable-search-path";

  private static final String     JUNIT_TEST_TIMEOUT_INSECONDS     = DYNAMIC_PROPERTIES_PREFIX
                                                                     + "junit-test-timeout-inseconds";

  public static final String      APP_SERVER_REPOSITORY_URL_BASE   = STATIC_PROPERTIES_PREFIX + "appserver.repository";

  public static final String      APP_SERVER_HOME                  = STATIC_PROPERTIES_PREFIX + "appserver.home";

  private static final String     APP_SERVER_FACTORY_NAME          = STATIC_PROPERTIES_PREFIX
                                                                     + "appserver.factory.name";

  private static final String     APP_SERVER_MAJOR_VERSION         = STATIC_PROPERTIES_PREFIX
                                                                     + "appserver.major-version";

  private static final String     APP_SERVER_MINOR_VERSION         = STATIC_PROPERTIES_PREFIX
                                                                     + "appserver.minor-version";

  private static final String     TRANSPARENT_TESTS_MODE           = STATIC_PROPERTIES_PREFIX
                                                                     + "transparent-tests.mode";
  private static final String     SPRING_TESTS_TIMEOUT             = STATIC_PROPERTIES_PREFIX + "spring.tests.timeout";

  private static final String     SYSTEM_PROPERTIES_RESOURCE_NAME  = "/test-system-properties.properties";

  private static final String     L2_STARTUP_PREFIX                = DYNAMIC_PROPERTIES_PREFIX + "l2.startup.";
  public static final String      L2_STARTUP_MODE                  = L2_STARTUP_PREFIX + "mode";
  public static final String      L2_STARTUP_JAVA_HOME             = L2_STARTUP_PREFIX + "jvm";

  private static TestConfigObject INSTANCE;

  private final Properties        properties;

  public static synchronized TestConfigObject getInstance() {
    if (INSTANCE == null) {
      try {
        INSTANCE = new TestConfigObject();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return INSTANCE;
  }

  private static void loadSystemProperties() throws IOException {
    InputStream in = null;

    if (System.getProperty(PROPERTY_FILE_LIST_PROPERTY_NAME) == null) {
      in = TestConfigObject.class.getResourceAsStream(SYSTEM_PROPERTIES_RESOURCE_NAME);
      if (in != null) {
        try {
          Properties systemProperties = new Properties();
          systemProperties.load(in);
          Iterator iter = systemProperties.entrySet().iterator();

          while (iter.hasNext()) {
            Map.Entry entry = (Map.Entry) iter.next();
            System.setProperty((String) entry.getKey(), (String) entry.getValue());
          }

          logger.info("Set " + systemProperties.size() + " system properties from resource '"
                      + SYSTEM_PROPERTIES_RESOURCE_NAME + "'.");
        } finally {
          in.close();
        }
      }
    }
  }

  private static void loadEnv() throws IOException {
    initBaseDir();

    if (!StringUtils.isBlank(System.getProperty(Directories.TC_INSTALL_ROOT_PROPERTY_NAME))) { throw new RuntimeException(
                                                                                                                          "Don't set '"
                                                                                                                              + Directories.TC_INSTALL_ROOT_PROPERTY_NAME
                                                                                                                              + "' in tests."); }
    System.setProperty(Directories.TC_INSTALL_ROOT_IGNORE_CHECKS_PROPERTY_NAME, "true");
    System.setProperty(Directories.TC_LICENSE_LOCATION_PROPERTY_NAME, baseDir.getCanonicalPath());
  }

  private static void initBaseDir() {
    String baseDirProp = System.getProperty(TC_BASE_DIR);
    if (baseDirProp == null || baseDirProp.trim().equals("")) invalidBaseDir();
    baseDir = new File(baseDirProp);
    if (!baseDir.isDirectory()) invalidBaseDir();
  }

  private static void invalidBaseDir() {
    baseDir = null;
    String value = System.getProperty(TC_BASE_DIR);
    StringBuffer buf = new StringBuffer();
    buf.append("The value of the system property " + TC_BASE_DIR + " is not valid.");
    buf.append(" The value is: \"").append(value).append("\"");
    throw new RuntimeException(buf.toString());
  }

  private TestConfigObject() throws IOException {
    this.properties = new Properties();
    StringBuffer loadedFrom = new StringBuffer();

    loadEnv();
    loadSystemProperties();

    int filesRead = 0;

    // *DO NOT* hardcode system properties in here just to make tests easier
    // to run in Eclipse.
    // Doing so makes it a *great* deal harder to modify the build system.
    // All you need to do
    // to run tests in Eclipse is to run 'tcbuild check_prep <module-name>
    // <test-type>' before
    // you run your tests.
    //
    // If these things are too hard for you to do, please come talk to the
    // build team. Hardcoding
    // properties here can make our lives very difficult.

    String[] components = {};
    if (System.getProperty(PROPERTY_FILE_LIST_PROPERTY_NAME) != null) {
      components = System.getProperty(PROPERTY_FILE_LIST_PROPERTY_NAME).split(File.pathSeparator);
    }

    for (int i = components.length - 1; i >= 0; --i) {
      File thisFile = new File(components[i]);
      if (thisFile.exists()) {
        Properties theseProperties = new Properties();
        theseProperties.load(new FileInputStream(thisFile));
        this.properties.putAll(theseProperties);
        if (filesRead > 0) loadedFrom.append(", ");
        loadedFrom.append("'" + thisFile.getAbsolutePath() + "'");
        ++filesRead;
      }
    }

    if (filesRead > 0) loadedFrom.append(", ");
    loadedFrom.append("system properties");

    this.properties.putAll(System.getProperties());

    logger.info("Loaded test configuration from " + loadedFrom.toString());
  }

  private String getProperty(String key, String defaultValue) {
    String result = this.properties.getProperty(key);
    if (result == null) {
      result = defaultValue;
    }
    return result;
  }

  private String getProperty(String key) {
    return getProperty(key, null);
  }

  public String getL2StartupMode() {
    return this.properties.getProperty(L2_STARTUP_MODE);
  }

  public boolean isL2StartupModeExternal() {
    return "external".equalsIgnoreCase(getL2StartupMode());
  }

  public String getL2StartupJavaHome() {
    String result = this.properties.getProperty(L2_STARTUP_JAVA_HOME);
    if (result == null && Vm.isJDK15Compliant()) {
      result = System.getProperty("java.home");
    }
    return result;
  }

  public String[] availableVariantsFor(String variantName) {
    String out = this.properties.getProperty(AVAILABLE_VARIANTS_PREFIX + variantName);
    if (StringUtils.isBlank(out)) return new String[0];
    return out.split(",");
  }

  public String variantLibraryClasspathFor(String variantName, String variantValue) {
    return this.properties.getProperty(VARIANT_LIBRARIES_PREFIX + variantName + "." + variantValue, "");
  }

  public String selectedVariantFor(String variantName) {
    String selected = this.properties.getProperty(SELECTED_VARIANT_PREFIX + variantName);
    if (null == selected) {
      selected = this.properties.getProperty(DEFAULT_VARIANT_PREFIX + variantName);
    }

    return selected;
  }

  /**
   * Returns the version string for the current JVM. Equivalent to
   * <code>System.getProperty("java.runtime.version")</code>.
   */
  public String jvmVersion() {
    return System.getProperty("java.runtime.version");
  }

  /**
   * Returns the type of the current JVM. Equivalent to <code>System.getProperty("java.vm.name")</code>.
   */
  public String jvmName() {
    return System.getProperty("java.vm.name");
  }

  public String osName() {
    return getProperty(OS_NAME);
  }

  public String platform() {
    String osname = osName();
    if (osname.startsWith("Windows")) {
      return "windows";
    } else if (osname.startsWith("Linux")) {
      return "linux";
    } else if (osname.startsWith("SunOS")) {
      return "solaris";
    } else return osname;
  }

  public String nativeLibName() {
    String osname = osName();
    if (osname.startsWith("Windows")) {
      return WINDOWS_NATIVE_LIB_NAME;
    } else if (osname.startsWith("Darwin")) {
      return OSX_NATIVE_LIB_NAME;
    } else {
      return UNIX_NATIVE_LIB_NAME;
    }
  }

  public String dataDirectoryRoot() {
    return getProperty(DATA_DIRECTORY_ROOT, new File(baseDir, "test-data").getAbsolutePath());
  }

  public String tempDirectoryRoot() {
    return getProperty(TEMP_DIRECTORY_ROOT, new File(baseDir, "temp").getAbsolutePath());
  }

  public String appserverHome() {
    return this.properties.getProperty(APP_SERVER_HOME);
  }

  public String appserverFactoryName() {
    String out = this.properties.getProperty(APP_SERVER_FACTORY_NAME);
    Assert.assertNotBlank(out);
    return out;
  }

  public String appserverMajorVersion() {
    String out = this.properties.getProperty(APP_SERVER_MAJOR_VERSION);
    Assert.assertNotBlank(out);
    return out;
  }

  public String appserverMinorVersion() {
    String out = this.properties.getProperty(APP_SERVER_MINOR_VERSION);
    Assert.assertNotBlank(out);
    return out;
  }

  public String springTestsTimeout() {
    return this.properties.getProperty(SPRING_TESTS_TIMEOUT);
  }

  public String executableSearchPath() {
    String nativeLibDirPath = this.properties.getProperty(EXECUTABLE_SEARCH_PATH);
    if (nativeLibDirPath.endsWith(NATIVE_LIB_LINUX_32) || nativeLibDirPath.endsWith(NATIVE_LIB_LINUX_64)) {
      int lastSeparator = nativeLibDirPath.lastIndexOf(File.separator);
      String vmType = System.getProperty("sun.arch.data.model");
      if (vmType.equals("32")) {
        nativeLibDirPath = nativeLibDirPath.substring(0, lastSeparator) + File.separator + NATIVE_LIB_LINUX_32;
      } else if (vmType.equals("64")) {
        nativeLibDirPath = nativeLibDirPath.substring(0, lastSeparator) + File.separator + NATIVE_LIB_LINUX_64;
      }
    }
    return nativeLibDirPath;
  }

  public File cacheDir() {
    String root = System.getProperty("user.home");
    if (Os.isWindows()) {
      File temp = new File("c:/temp");
      if (!temp.exists()) {
        temp = new File(root.substring(0, 2) + "/temp");
      }
      return temp;
    }
    return new File(root, ".tc");
  }

  public File appserverServerInstallDir() {
    File installDir = new File(cacheDir(), "appservers");
    if (!installDir.exists()) installDir.mkdirs();
    return installDir;
  }

  public String normalBootJar() {
    String out = this.properties.getProperty(BOOT_JAR_NORMAL);
    Assert.assertNotBlank(out);
    assertFileExists(out);
    return out;
  }

  public String linkedChildProcessClasspath() {
    String out = this.properties.getProperty(LINKED_CHILD_PROCESS_CLASSPATH);
    Assert.assertNotBlank(out);
    assertValidClasspath(out);
    return out;
  }

  public String sessionClasspath() {
    String out = this.properties.getProperty(SESSION_CLASSPATH);
    Assert.assertNotBlank(out);
    assertValidClasspath(out);
    return out;
  }

  public int getJunitTimeoutInSeconds() {
    return Integer.parseInt(getProperty(JUNIT_TEST_TIMEOUT_INSECONDS, "900"));
  }

  public static final String    TRANSPARENT_TESTS_MODE_NORMAL         = "normal";
  public static final String    TRANSPARENT_TESTS_MODE_CRASH          = "crash";
  public static final String    TRANSPARENT_TESTS_MODE_ACTIVE_PASSIVE = "active-passive";

  private static File           baseDir;

  public String transparentTestsMode() {
    return getProperty(TRANSPARENT_TESTS_MODE, TRANSPARENT_TESTS_MODE_NORMAL);
  }

  private void assertValidClasspath(String out) {
    String[] pathElements = out.split(File.pathSeparator);
    for (int i = 0; i < pathElements.length; i++) {
      String pathElement = pathElements[i];
      Assert.assertTrue("Path element is non-existent: " + pathElement, new File(pathElement).exists());

    }
  }

  private void assertFileExists(String out) {
    File file = new File(out);
    Assert.assertTrue("not a file: " + out, file.isFile());
  }
}
