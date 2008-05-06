/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object.tools;

public abstract class BootJarException extends Exception {
  
  protected BootJarException(String message){
    super(message);
  }
  
  protected BootJarException(String message, Throwable t){
    super(message, t);
  }
  
}
