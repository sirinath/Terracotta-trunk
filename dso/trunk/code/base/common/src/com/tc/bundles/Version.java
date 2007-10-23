/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.bundles;

final class Version extends org.osgi.framework.Version {

  public Version(String arg0) {
    super(arg0.replace('-', '.'));
  }

  public Version(int major, int minor, int micro) {
    super(major, minor, micro);
  }

  public Version(int major, int minor, int micro, String qualifier) {
    super(major, minor, micro, qualifier);
  }

  public static Version parse(String version) {
    org.osgi.framework.Version v = org.osgi.framework.Version.parseVersion(version.replace('-', '.'));
    return new Version(v.getMajor(), v.getMinor(), v.getMicro(), v.getQualifier());
  }
}
