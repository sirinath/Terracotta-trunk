/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.util;

public interface ToolkitSubtypeStatus {
  int getCurrentRejoinCount();

  boolean isDestroyed();

}
