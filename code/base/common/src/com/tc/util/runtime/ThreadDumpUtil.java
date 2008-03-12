/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.util.runtime;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;

import java.lang.reflect.Method;

public class ThreadDumpUtil {

  private static final TCLogger logger = TCLogging.getLogger(ThreadDumpUtil.class);

  private static Class threadDumpUtilJdk15Type;
  private static final Class[] EMPTY_PARAM_TYPES = new Class[0];
  private static final Object[] EMPTY_PARAMS = new Object[0];

  static {
    try {
      threadDumpUtilJdk15Type = Class.forName("com.tc.util.runtime.ThreadDumpUtilJdk15");
    } catch (ClassNotFoundException cnfe) {
      threadDumpUtilJdk15Type = null;
    }
  }

  public static String getThreadDump() {
    final Exception exception;
    try {
      if (threadDumpUtilJdk15Type == null) {
        return "Thread dumps require JRE-1.5 or greater";
      }
      Method method = threadDumpUtilJdk15Type.getMethod("getThreadDump", EMPTY_PARAM_TYPES);
      return (String)method.invoke(null, EMPTY_PARAMS);
    } catch (Exception e) {
      logger.error("Cannot take thread dumps - " + e.getMessage(), e);
      exception = e;
    }
    return "Cannot take thread dumps " + (exception != null ? exception.getMessage() : "");
  }

  public static void main(String[] args) {
    System.out.println(getThreadDump());
  }
}