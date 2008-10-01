/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.runtime.cache;

import com.tc.runtime.MemoryUsage;

public interface CacheMemoryEventsListener {
  
  public void memoryUsed(CacheMemoryEventType type, MemoryUsage usage);
  
}
