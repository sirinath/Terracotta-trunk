/**
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Fetches temporary directories (and files) for use in tests. The directory is cleaned the first time it is fetched;
 * files may exist (or no), as appropriate.
 */
public class TempDirectoryHelper extends BaseDirectoryHelper {

  private final boolean cleanDir;

  public TempDirectoryHelper(Class theClass, boolean cleanDir) {
    this(theClass, TestConfigObject.getInstance().tempDirectoryRoot(), cleanDir);
  }

  public TempDirectoryHelper(Class theClass) {
    this(theClass, TestConfigObject.getInstance().tempDirectoryRoot(), true);
  }

  // For internal use and tests only.
  TempDirectoryHelper(Class theClass, String directoryPath, boolean cleanDir) {
    super(theClass, directoryPath);
    this.cleanDir = cleanDir;
  }

  protected File fetchDirectory() throws IOException {
    File root = getRoot();
    if (!root.exists()) {
      root.mkdirs();
    }
    String shortClassName = getTargetClass().getName();
    String[] tokens = shortClassName.split("\\.");
    if (tokens.length > 1) {
      shortClassName = tokens[tokens.length-1];
    }
    File out = new File(root, shortClassName);
    if ((!out.exists()) && (!out.mkdirs())) {
      FileNotFoundException fnfe = new FileNotFoundException("Directory '" + out.getAbsolutePath()
          + "' can't be created.");
      throw fnfe;
    }

    if (cleanDir) {
      FileUtils.cleanDirectory(out);
    }
    return out;
  }

}