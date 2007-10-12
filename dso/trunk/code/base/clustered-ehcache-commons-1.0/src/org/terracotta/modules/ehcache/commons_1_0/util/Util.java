/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package org.terracotta.modules.ehcache.commons_1_0.util;

import com.tc.config.lock.LockLevel;
import com.tc.object.bytecode.ManagerUtil;
import com.tc.properties.TCProperties;

public class Util {
  private final static String LOCK_CONCURRENT = "CONCURRENT";
  
  public static int hash(int h) {
    h += ~(h << 9);
    h ^= (h >>> 14);
    h += (h << 4);
    h ^= (h >>> 10);
    return h;
  }
  
  public static int getEhcacheReadLockLevel() {
    int lockLevel = LockLevel.READ;
    TCProperties ehcacheProperies = ManagerUtil.getTCProperties().getPropertiesFor("ehcache.lock");
    if (ehcacheProperies != null) {
      String lockLevelStr = ehcacheProperies.getProperty("readLevel");
      if (LOCK_CONCURRENT.equals(lockLevelStr)) {
        lockLevel = LockLevel.CONCURRENT;
      }
    }
    return lockLevel;
  }
  
  public static int getEhcacheWriteLockLevel() {
    int lockLevel = LockLevel.WRITE;
    TCProperties ehcacheProperies = ManagerUtil.getTCProperties().getPropertiesFor("ehcache.lock");
    if (ehcacheProperies != null) {
      String lockLevelStr = ehcacheProperies.getProperty("writeLevel");
      if (LOCK_CONCURRENT.equals(lockLevelStr)) {
        lockLevel = LockLevel.CONCURRENT;
      }
    }
    return lockLevel;
  }

}
