/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.object.cache;

import com.tc.object.locks.LockID;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;

import java.util.concurrent.TimeUnit;

public class IncoherentCachedItem extends CachedItem {

  private static final long SERVERMAP_INCOHERENT_CACHED_ITEMS_RECYCLE_TIME_MILLIS = TCPropertiesImpl
                                                                                      .getProperties()
                                                                                      .getLong(
                                                                                               TCPropertiesConsts.EHCACHE_STORAGESTRATEGY_DCV2_LOCALCACHE_INCOHERENT_READ_TIMEOUT);

  private final long        lastCoherentTime;

  public IncoherentCachedItem(final CachedItem item) {
    this(item.getListener(), item.getLockID(), item.getKey(), item.getValue());
  }

  public IncoherentCachedItem(final DisposeListener listener, final LockID lockID, final Object key, final Object value) {
    super(listener, lockID, key, value);
    this.lastCoherentTime = System.nanoTime();
  }

  public boolean isIncoherentTooLong() {
    return TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - this.lastCoherentTime)) >= SERVERMAP_INCOHERENT_CACHED_ITEMS_RECYCLE_TIME_MILLIS;
  }

}
