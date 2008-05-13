/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.admin;

import com.tc.logging.TCLogging;


public class ProductInfo {
  private String              m_version;
  private String              m_buildID;
  private String              m_license;
  private String              m_copyright;

  public static final String DEFAULT_LICENSE = "Unlimited development";

  public ProductInfo() {
    TCLogging.class.toString();
    com.tc.util.ProductInfo productInfo = com.tc.util.ProductInfo.getInstance();

    m_version = productInfo.rawVersion();
    m_buildID = productInfo.buildID();
    m_license = DEFAULT_LICENSE; // FIXME 2005-12-15 andrew
    m_copyright = productInfo.copyright();
  }

  public ProductInfo(String version, String buildID, String license, String copyright) {
    m_version = version;
    m_buildID = buildID;
    m_license = license;
    m_copyright = copyright;
  }

  public String getVersion() {
    return m_version;
  }

  public String getBuildID() {
    return m_buildID;
  }

  public String getLicense() {
    return m_license;
  }

  public String getCopyright() {
    return m_copyright;
  }
}
