/*
 * All content copyright (c) 2003-2009 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.license.util;

import com.tc.license.License;

import java.io.InputStream;
import java.io.OutputStream;

public interface LicenseFormat {
  public License loadAndVerify(InputStream in, Signer signer);

  public void store(OutputStream out, License license);
}
