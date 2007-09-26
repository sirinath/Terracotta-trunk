/**
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test;

import com.tc.util.runtime.Vm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that the VM that the buildsystem thinks it's running the tests with is the VM that it's *actually* running the
 * tests with.
 */
public class CorrectJVMTestBase extends TCTestCase {

  private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+(_\\d+)?)(-\\S+)?");

  public void testVersion() throws Exception {
    if (Vm.isIBM()) {
      // IBM has a non-uniform version string that makes no sense to test in an automated way
      return;
    }
    String actualVersion = System.getProperty("java.runtime.version");
    String expectedVersion = TestConfigObject.getInstance().jvmVersion();

    Matcher matcher = VERSION_PATTERN.matcher(actualVersion);

    assertTrue("Actual version of '" + actualVersion + "' matches pattern", matcher.matches());
    assertEquals(expectedVersion, matcher.group(1));
  }

  public void testType() throws Exception {
    String vmName = System.getProperty("java.vm.name").toLowerCase();
    String expectedType = TestConfigObject.getInstance().jvmType().trim().toLowerCase();

    assertTrue("Actual type of '" + vmName + "' includes expected type of '" + expectedType + "'", vmName
        .indexOf(expectedType) >= 0);
  }

}
