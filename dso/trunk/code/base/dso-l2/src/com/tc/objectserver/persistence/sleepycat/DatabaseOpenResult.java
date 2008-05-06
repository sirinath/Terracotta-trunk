/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.objectserver.persistence.sleepycat;

public class DatabaseOpenResult {
  private final boolean clean;
  
  DatabaseOpenResult(boolean clean) {
    this.clean = clean;
  }
  
  public boolean isClean() {
    return this.clean;
  }
  
}
