/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.util.runtime;

import org.terracotta.NativeToolHandler;

import com.tc.test.TestConfigObject;

import java.io.File;

class GetPid {

  private GetPid() {
  // nothing here
  }

  public static void main(String args[]) {
    System.out.println("PID is " + getPID());
  }

  public static int getPID() {
    if (libOK) { return new GetPid().getPid(); }
    throw new RuntimeException("The JNI library did not load correctly, the stack was printed to stderr earlier");
  }

  private native int getPid();

  private static final boolean libOK;

  static {
    boolean ok = false;
    NativeToolHandler toolHandler = new NativeToolHandler();
    String nativeLibPath = toolHandler.getToolLocation().getAbsolutePath() + File.separator
        + TestConfigObject.getInstance().nativeLibName();
    try {
      System.load(nativeLibPath);
      ok = true;
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      System.err.println("\n***************************\nNative lib path is [" + nativeLibPath
          + "]\n***************************\n");
      System.err.flush();
    }

    libOK = ok;
  }

}
