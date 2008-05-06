/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.objectserver.impl;

import com.tc.objectserver.api.ObjectManagerStats;
import com.tc.objectserver.api.ObjectManagerStatsListener;
import com.tc.stats.counter.sampled.SampledCounter;
import com.tc.stats.counter.sampled.TimeStampedCounterValue;

/**
 * Implements the object manager stats
 */
public class ObjectManagerStatsImpl implements ObjectManagerStatsListener, ObjectManagerStats {

  private long                 cacheHits      = 0L;
  private long                 cacheMisses    = 0L;
  private long                 objectsCreated = 0L;
  private final SampledCounter newObjectCounter;
  private final SampledCounter faultRateCounter;

  public ObjectManagerStatsImpl(SampledCounter newObjectCounter, SampledCounter faultRateCounter) {
    this.newObjectCounter = newObjectCounter;
    this.faultRateCounter = faultRateCounter;
  }

  public synchronized void cacheHit() {
    this.cacheHits++;
  }

  public synchronized void cacheMiss() {
    this.cacheMisses++;
    this.faultRateCounter.increment();
  }

  public synchronized void newObjectCreated() {
    this.objectsCreated++;
    this.newObjectCounter.increment();
  }

  public synchronized double getCacheHitRatio() {
    return ((double) cacheHits) / ((double) getTotalRequests());
  }

  public synchronized long getTotalRequests() {
    return this.cacheHits + this.cacheMisses;
  }

  public synchronized long getTotalCacheMisses() {
    return this.cacheMisses;
  }

  public synchronized long getTotalCacheHits() {
    return this.cacheHits;
  }

  public long getTotalObjectsCreated() {
    return this.objectsCreated;
  }

  public TimeStampedCounterValue getCacheMissRate() {
    return this.faultRateCounter.getMostRecentSample();
  }

}
