/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.bundles;

import org.apache.commons.io.FileUtils;
import org.osgi.framework.BundleException;

import com.tc.properties.TCPropertiesImpl;
import com.terracottatech.config.Module;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Iterator;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import junit.framework.TestCase;

public class KnopflerfishOSGiTest extends TestCase {

  private static final String PRODUCT_VERSION_DASH_QUALIFIER = "2.6.0-SNAPSHOT";
  private static final String PRODUCT_VERSION_DOT_QUALIFIER  = PRODUCT_VERSION_DASH_QUALIFIER.replace('-', '.');
  private KnopflerfishOSGi    osgiRuntime                    = null;

  public void setUp() throws Exception {
    osgiRuntime = new KnopflerfishOSGi(new URL[0]);
  }

  public void tearDown() {
    osgiRuntime = null;
  }

  /**
   * Test that checks the Terracotta-RequireVersion embedded for all all the TIMs that are bundled with the kit.
   * 
   * @throws BundleException
   * @throws IOException
   */
  public void testRequireVersionForAllModules() throws IOException, BundleException {
    for (Iterator i = jarFiles().iterator(); i.hasNext();) {
      File jar = new File(i.next().toString());
      String version = PRODUCT_VERSION_DASH_QUALIFIER;
      String name = jar.getName().replaceAll("-" + version + ".jar", "");

      URL[] repos = { new URL(System.getProperty("com.tc.l1.modules.repositories")) };
      Resolver resolver = new Resolver(repos);
      Module module = Module.Factory.newInstance();
      module.setName(name);
      module.setVersion(version);
      module.setGroupId("org.terracotta.modules");
      URL url = resolver.resolve(module);
      assertEquals(url.getPath().endsWith(name + "-" + version + ".jar"), true);

      final JarFile bundle = new JarFile(FileUtils.toFile(url));
      final Manifest manifest = bundle.getManifest();
      final String requireversion = manifest.getMainAttributes().getValue("Terracotta-RequireVersion");
      final String symbolicName = manifest.getMainAttributes().getValue("Bundle-SymbolicName");

      assertNotNull("Terracotta-RequireVersion attribute for " + symbolicName + " module must not be null.",
                    requireversion);
      assertTrue("Terracotta-RequireVersion attribute for " + symbolicName + " module must not be empty.",
                 requireversion.length() > 0);
      assertEquals("Terracotta-RequireVersion attribute for " + symbolicName + " module mis expected to be "
                   + PRODUCT_VERSION_DOT_QUALIFIER, requireversion, PRODUCT_VERSION_DOT_QUALIFIER);

      String mode = IVersionCheck.OFF;
      int actual = osgiRuntime.versionCheck(mode, requireversion, PRODUCT_VERSION_DASH_QUALIFIER);
      assertEquals(IVersionCheck.IGNORED, actual);
      actual = osgiRuntime.versionCheck(mode, requireversion, PRODUCT_VERSION_DOT_QUALIFIER);
      assertEquals(IVersionCheck.IGNORED, actual);
      actual = osgiRuntime.versionCheck(mode, requireversion, "9.9.9");
      assertEquals(IVersionCheck.IGNORED, actual);

      mode = IVersionCheck.WARN;
      actual = osgiRuntime.versionCheck(mode, requireversion, PRODUCT_VERSION_DASH_QUALIFIER);
      assertEquals(IVersionCheck.OK, actual);
      actual = osgiRuntime.versionCheck(mode, requireversion, PRODUCT_VERSION_DOT_QUALIFIER);
      assertEquals(IVersionCheck.OK, actual);
      actual = osgiRuntime.versionCheck(mode, requireversion, "9.9.9");
      assertEquals(IVersionCheck.WARN_INCORRECT_VERSION, actual);

      mode = IVersionCheck.ENFORCE;
      actual = osgiRuntime.versionCheck(mode, requireversion, PRODUCT_VERSION_DASH_QUALIFIER);
      assertEquals(IVersionCheck.OK, actual);
      actual = osgiRuntime.versionCheck(mode, requireversion, PRODUCT_VERSION_DOT_QUALIFIER);
      assertEquals(IVersionCheck.OK, actual);
      actual = osgiRuntime.versionCheck(mode, requireversion, "9.9.9");
      assertEquals(IVersionCheck.ERROR_INCORRECT_VERSION, actual);

      mode = IVersionCheck.STRICT;
      actual = osgiRuntime.versionCheck(mode, requireversion, PRODUCT_VERSION_DASH_QUALIFIER);
      assertEquals(IVersionCheck.OK, actual);
      actual = osgiRuntime.versionCheck(mode, requireversion, PRODUCT_VERSION_DOT_QUALIFIER);
      assertEquals(IVersionCheck.OK, actual);
      actual = osgiRuntime.versionCheck(mode, requireversion, "9.9.9");
      assertEquals(IVersionCheck.ERROR_INCORRECT_VERSION, actual);
    }
  }

  private Collection jarFiles() throws IOException {
    URL url = new URL(System.getProperty("com.tc.l1.modules.repositories"));
    return FileUtils.listFiles(FileUtils.toFile(url), new String[] { "jar" }, true);
  }

  /**
   * Test that the Terracotta-RequireVersion value abides by the expected version pattern/format, and that it is caught
   * if it isn't.
   */
  public void testRequireVersionPatternCheck() {
    useGoodRequireVersion("off", IVersionCheck.IGNORED);
    useGoodRequireVersion("warn", IVersionCheck.OK);
    useGoodRequireVersion("enforce", IVersionCheck.OK);
    useGoodRequireVersion("strict", IVersionCheck.OK);

    useBadRequireVersion("off", IVersionCheck.IGNORED);
    useBadRequireVersion("warn", IVersionCheck.ERROR_BAD_REQUIRE_ATTRIBUTE);
    useBadRequireVersion("enforce", IVersionCheck.ERROR_BAD_REQUIRE_ATTRIBUTE);
    useBadRequireVersion("strict", IVersionCheck.ERROR_BAD_REQUIRE_ATTRIBUTE);
  }

  private void useGoodRequireVersion(String mode, int expected) {
    setProperty("l1.modules.tc-version-check", mode);
    int actual = osgiRuntime.versionCheck(mode, "1.0.0.SNAPSHOT", "1.0.0.SNAPSHOT");
    assertEquals(expected, actual);

    actual = osgiRuntime.versionCheck(mode, "1.0.0", "1.0.0");
    assertEquals(expected, actual);

    actual = osgiRuntime.versionCheck(mode, "1.1.1.1", "1.1.1.1");
    assertEquals(expected, actual);

    actual = osgiRuntime.versionCheck(mode, "1", "1");
    assertEquals(expected, actual);

    actual = osgiRuntime.versionCheck(mode, "1.1.0-SNAPSHOT", "1.1.0-SNAPSHOT");
    assertEquals(expected, actual);
  }

  private void useBadRequireVersion(String mode, int expected) {
    setProperty("l1.modules.tc-version-check", mode);

    int actual = osgiRuntime.versionCheck(mode, "A.B.C-SNAPSHOT", "A.B.C-SNAPSHOT");
    assertEquals(expected, actual);

    actual = osgiRuntime.versionCheck(mode, "A.B.C.SNAPSHOT", "A.B.C.SNAPSHOT");
    assertEquals(expected, actual);

    actual = osgiRuntime.versionCheck(mode, "1.A.Z.SNAPSHOT", "1.A.Z.SNAPSHOT");
    assertEquals(expected, actual);
  }

  /**
   * Test that null or empty Terracotta-RequireVersion is forgiven or caught depending on the version check mode.
   */
  public void testNullOrEmptyRequireVersion() {
    useNullOrEmptyRequireVersion("off", null, IVersionCheck.IGNORED);
    useNullOrEmptyRequireVersion("warn", null, IVersionCheck.WARN_REQUIRE_ATTRIBUTE_MISSING);
    useNullOrEmptyRequireVersion("enforce", null, IVersionCheck.WARN_REQUIRE_ATTRIBUTE_MISSING);
    useNullOrEmptyRequireVersion("strict", null, IVersionCheck.ERROR_REQUIRE_ATTRIBUTE_MISSING);

    useNullOrEmptyRequireVersion("off", "", IVersionCheck.IGNORED);
    useNullOrEmptyRequireVersion("warn", "", IVersionCheck.WARN_REQUIRE_ATTRIBUTE_MISSING);
    useNullOrEmptyRequireVersion("enforce", "", IVersionCheck.WARN_REQUIRE_ATTRIBUTE_MISSING);
    useNullOrEmptyRequireVersion("strict", "", IVersionCheck.ERROR_REQUIRE_ATTRIBUTE_MISSING);
  }

  private void useNullOrEmptyRequireVersion(String mode, String version, int expected) {
    setProperty("l1.modules.tc-version-check", mode);
    int actual = osgiRuntime.versionCheck(mode, version, "1.0.0");
    assertEquals(expected, actual);
  }

  /**
   * Test that version comparisons pass or fail depending on the version check mode.
   */
  public void testVersionMatching() {
    compareVersion("off", "1.0.0", "1.0.0", IVersionCheck.IGNORED);
    compareVersion("off", "1.0.0", "2.0.0", IVersionCheck.IGNORED);

    compareVersion("warn", "1.0.0", "1.0.0", IVersionCheck.OK);
    compareVersion("warn", "1.0.0", "2.0.0", IVersionCheck.WARN_INCORRECT_VERSION);

    compareVersion("enforce", "1.0.0", "1.0.0", IVersionCheck.OK);
    compareVersion("enforce", "1.0.0", "2.0.0", IVersionCheck.ERROR_INCORRECT_VERSION);

    compareVersion("strict", "1.0.0", "1.0.0", IVersionCheck.OK);
    compareVersion("strict", "1.0.0", "2.0.0", IVersionCheck.ERROR_INCORRECT_VERSION);
  }

  private void compareVersion(String mode, String version, String tcversion, int expected) {
    setProperty("l1.modules.tc-version-check", mode);
    int actual = osgiRuntime.versionCheck(mode, version, tcversion);
    assertEquals(expected, actual);
  }

  /**
   * Test that valid modes are recognized
   */
  public void testSetVersionCheckMode() {
    try {
      setProperty("l1.modules.tc-version-check", "off");
      String mode = osgiRuntime.versionCheckMode();
      assertEquals(IVersionCheck.OFF, mode);

      setProperty("l1.modules.tc-version-check", "warn");
      mode = osgiRuntime.versionCheckMode();
      assertEquals(IVersionCheck.WARN, mode);

      setProperty("l1.modules.tc-version-check", "enforce");
      mode = osgiRuntime.versionCheckMode();
      assertEquals(IVersionCheck.ENFORCE, mode);

      setProperty("l1.modules.tc-version-check", "strict");
      mode = osgiRuntime.versionCheckMode();
      assertEquals(IVersionCheck.STRICT, mode);
    } catch (BundleException ex) {
      fail(ex.getMessage());
    }
  }

  /**
   * Test that default mode is set when empty
   */
  public void testDefaultVersionCheckMode() {
    try {
      setProperty("l1.modules.tc-version-check", "");
      String mode = osgiRuntime.versionCheckMode();
      assertEquals(IVersionCheck.OFF, mode);
    } catch (BundleException ex) {
      fail(ex.getMessage());
    }
  }

  /**
   * Test that invalid modes are caught
   */
  public void testBadVersionCheckMode() {
    String mode = null;
    try {
      setProperty("l1.modules.tc-version-check", "foobar");
      mode = osgiRuntime.versionCheckMode();

      setProperty("l1.modules.tc-version-check", "OFF");
      mode = osgiRuntime.versionCheckMode();

      setProperty("l1.modules.tc-version-check", "WARN");
      mode = osgiRuntime.versionCheckMode();

      setProperty("l1.modules.tc-version-check", "ENFORCE");
      mode = osgiRuntime.versionCheckMode();

      setProperty("l1.modules.tc-version-check", "STRICT");
      mode = osgiRuntime.versionCheckMode();
    } catch (BundleException ex) {
      assertNull(mode);
      return;
    }
    fail("Invalid mode value set for property l1.modules.tc-version-check: " + mode);
  }

  private void setProperty(String key, String value) {
    TCPropertiesImpl.setProperty(key, value);
  }

}
