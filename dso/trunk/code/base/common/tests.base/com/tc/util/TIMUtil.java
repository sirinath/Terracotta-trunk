/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.util;

import com.tc.bundles.BundleSpec;

import java.io.IOException;
import java.util.Properties;

/**
 * Terracotta Integration Module Util This should be the only source where the TIM names and versions are defined. Check
 * content of integration-modules.properties
 */
public class TIMUtil {
  public static final String      SUREFIRE_2_3;
  public static final String      JETTY_6_1;

  public static final String      TOMCAT_5_0;
  public static final String      TOMCAT_5_5;
  public static final String      TOMCAT_6_0;

  public static final String      JBOSS_3_2;
  public static final String      JBOSS_4_0;
  public static final String      JBOSS_4_2;

  public static final String      WEBLOGIC_9;
  public static final String      WEBLOGIC_10;

  public static final String      WASCE_1_0;

  public static final String      GLASSFISH_V1;
  public static final String      GLASSFISH_V2;

  public static final String      RESIN;

  private static final Properties modules = new Properties();

  static {
    try {
      modules.load(TIMUtil.class.getResourceAsStream("integration-modules.properties"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    SUREFIRE_2_3 = lookup(".*surefire-2.3");
    JETTY_6_1 = "tim-jetty-6.1";

    TOMCAT_5_0 = "tim-tomcat-5.0";
    TOMCAT_5_5 = "tim-tomcat-5.5";
    TOMCAT_6_0 = "tim-tomcat-6.0";
    JBOSS_3_2 = "tim-jboss-3.2";
    JBOSS_4_0 = "tim-jboss-4.0";
    JBOSS_4_2 = "tim-jboss-4.2";
    WEBLOGIC_9 = "tim-weblogic-9";
    WEBLOGIC_10 = "tim-weblogic-10";
    WASCE_1_0 = "tim-wasce-1.0";
    GLASSFISH_V1 = "tim-glassfish-v1";
    GLASSFISH_V2 = "tim-glassfish-v2";
    RESIN = "tim-resin";
  }

  private TIMUtil() {
    // singleton
  }

  private static String lookup(String pattern) {
    String name = searchModuleName(pattern);
    if (name == null) { throw new RuntimeException("Can't find module with pattern: [" + pattern + "]"); }
    return name;
  }

  /**
   * @param pattern: java regular expression
   */
  private static String searchModuleName(String pattern) {
    if (modules.containsKey(pattern)) { return pattern; }
    String name = null;
    for (Object element : modules.keySet()) {
      String moduleName = (String) element;
      if (moduleName.matches(pattern)) {
        name = moduleName;
        break;
      }
    }
    return name;
  }

  public static String getVersion(String moduleName) {
    String spec = modules.getProperty(moduleName);
    BundleSpec bundleSpec = BundleSpec.newInstance(spec);
    return bundleSpec.getVersion();
  }
}
