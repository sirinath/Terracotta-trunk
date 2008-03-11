/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.cache;

import com.tc.lang.TCThreadGroup;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.runtime.MemoryEventType;
import com.tc.runtime.MemoryEventsListener;
import com.tc.runtime.MemoryUsage;
import com.tc.runtime.TCMemoryManagerImpl;
import com.tc.statistics.AgentStatisticsManager;
import com.tc.statistics.StatisticData;
import com.tc.statistics.StatisticsAgentSubSystem;
import com.tc.statistics.exceptions.TCAgentStatisticsManagerException;
import com.tc.util.Assert;
import com.tc.util.State;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

public class CacheManager implements MemoryEventsListener {

  public static final String CACHE_OBJECTS_EVICT_REQUEST = "cache objects evict request";
  public static final String CACHE_OBJECTS_EVICTED = "cache objects evicted";

  private static final TCLogger     logger              = TCLogging.getLogger(CacheManager.class);

  private static final State        INIT                = new State("INIT");
  private static final State        PROCESSING          = new State("PROCESSING");
  private static final State        COMPLETE            = new State("COMPLETE");

  private final Evictable           evictable;
  private final CacheConfig         config;
  private final TCMemoryManagerImpl memoryManager;

  private int                       calculatedCacheSize = 0;
  private CacheStatistics           lastStat            = null;
  private final StatisticsAgentSubSystem  statisticsAgentSubSystem;

  public CacheManager(Evictable evictable, CacheConfig config, TCThreadGroup threadGroup, StatisticsAgentSubSystem statisticsAgentSubSystem) {
    this.evictable = evictable;
    this.config = config;
    this.memoryManager = new TCMemoryManagerImpl(config.getUsedThreshold(), config.getUsedCriticalThreshold(), config
        .getSleepInterval(), config.getLeastCount(), config.isOnlyOldGenMonitored(), threadGroup);
    this.memoryManager.registerForMemoryEvents(this);
    if (config.getObjectCountCriticalThreshold() > 0) {
      logger
          .warn("Cache Object Count Critical threshold is set to "
                + config.getObjectCountCriticalThreshold()
                + ". It is not recommended that this value is set. Setting a wrong vlaue could totally destroy performance.");
    }
    this.statisticsAgentSubSystem = statisticsAgentSubSystem;
  }

  public void memoryUsed(MemoryEventType type, MemoryUsage usage) {
    CacheStatistics cp = new CacheStatistics(type, usage);
    evictable.evictCache(cp);
    cp.validate();
    addLastStat(cp);
  }

  // currently we only maintain 1 last stat
  private void addLastStat(CacheStatistics cp) {
    this.lastStat = cp;
  }

  private final class CacheStatistics implements CacheStats {
    private final MemoryEventType type;
    private final MemoryUsage     usage;

    private int                   countBefore;
    private int                   countAfter;
    private int                   evicted;
    private boolean               objectsGCed = false;
    private int                   toEvict;
    private long                  startTime;
    private State                 state       = INIT;

    public CacheStatistics(MemoryEventType type, MemoryUsage usage) {
      this.type = type;
      this.usage = usage;
    }

    public void validate() {
      if (state == PROCESSING) {
        // This might be ignored by the memory manager thread. TODO:: exit VM !!!
        throw new AssertionError(this + " : Object Evicted is not called. This indicates a bug in the software !");
      }
    }

    public int getObjectCountToEvict(int currentCount) {
      startTime = System.currentTimeMillis();
      countBefore = currentCount;
      adjustCachedObjectCount(currentCount);
      toEvict = computeObjects2Evict(currentCount);
      if (toEvict < 0 || toEvict > currentCount) {
        //
        throw new AssertionError("Computed Object to evict is out of range : toEvict = " + toEvict + " currentCount = "
                                 + currentCount + " " + this);
      }
      if (toEvict > 0) {
        state = PROCESSING;
      }
      if (config.isLoggingEnabled()) {
        final int usedPercentage = usage.getUsedPercentage();
        final long collectionCount = usage.getCollectionCount();
        logger.info("Asking to evict " + toEvict + " current size = " + currentCount + " calculated cache size = "
                    + calculatedCacheSize + " heap used = " + usedPercentage + " %  gc count = "
                    + collectionCount);
        if (statisticsAgentSubSystem != null && statisticsAgentSubSystem.isActive()) {
          storeCacheEvictRequestStats(currentCount, toEvict, calculatedCacheSize, usedPercentage, collectionCount);
        }
      }
      return this.toEvict;
    }

    private synchronized void storeCacheEvictRequestStats(int currentCount, int toEvict, int calculatedCacheSize, int usedPercentage, long collectionCount) {
      Date moment = new Date();
      AgentStatisticsManager agentStatisticsManager = (AgentStatisticsManager)statisticsAgentSubSystem.getStatisticsManager();
      Collection sessions = agentStatisticsManager.getActiveSessionIDsForAction(CACHE_OBJECTS_EVICT_REQUEST);
      if (sessions != null && sessions.size() > 0) {
        StatisticData[] datas = getCacheObjectsEvictRequestData(currentCount, toEvict, calculatedCacheSize, usedPercentage, collectionCount);
        storeStatisticsDatas(moment, sessions, datas);
      }
    }

    private synchronized StatisticData[] getCacheObjectsEvictRequestData(int currentCount, int toEvict, int calculatedCacheSize, int usedPercentage, long collectionCount) {
      List datas = new ArrayList();
      StatisticData statisticData = new StatisticData(CACHE_OBJECTS_EVICT_REQUEST, "asking to evict count", new Long(toEvict));
      datas.add(statisticData);

      statisticData = new StatisticData(CACHE_OBJECTS_EVICT_REQUEST, "current size", new Long(currentCount));
      datas.add(statisticData);

      statisticData = new StatisticData(CACHE_OBJECTS_EVICT_REQUEST, "calculated cache size", new Long(calculatedCacheSize));
      datas.add(statisticData);

      statisticData = new StatisticData(CACHE_OBJECTS_EVICT_REQUEST, "percentage heap used", new Long(usedPercentage));
      datas.add(statisticData);

      statisticData = new StatisticData(CACHE_OBJECTS_EVICT_REQUEST, "gc count", new Long(collectionCount));
      datas.add(statisticData);

      return (StatisticData[])datas.toArray(new StatisticData[0]);
    }

    //
    // We recalibrate the calculatedCacheSize based on the current known details if one of the following is true.
    // 0) Usage goes below threshold or
    // 1) This is the first threshold crossing alarm or
    // 2) A GC has taken place since the last time (we either base in on the collection count which is accurate in 1.5
    // or in 1.4 we check to see if the usedMemory has gone down which is an indication of gc (not foolprove though)
    //
    private void adjustCachedObjectCount(int currentCount) {
      if (type == MemoryEventType.BELOW_THRESHOLD || lastStat == null
          || lastStat.usage.getCollectionCount() < usage.getCollectionCount()
          || (usage.getCollectionCount() < 0 && lastStat.usage.getUsedMemory() > usage.getUsedMemory())) {
        double used = usage.getUsedPercentage();
        double threshold = config.getUsedThreshold();
        Assert.assertTrue((type == MemoryEventType.BELOW_THRESHOLD && threshold >= used) || threshold <= used);
        if (used > 0) calculatedCacheSize = (int) (currentCount * (threshold / used));
      }
    }

    public void objectEvicted(int evictedCount, int currentCount, List targetObjects4GC) {
      this.evicted = evictedCount;
      this.countAfter = currentCount;
      state = COMPLETE;
      // TODO:: add reference queue
      if (config.isLoggingEnabled()) {
        final int newObjectsCount = getNewObjectsCount();
        final long timeTaken = System.currentTimeMillis() - startTime;
        logger.info("Evicted " + evictedCount + " current Size = " + currentCount + " new objects created = "
                    + newObjectsCount + " time taken = " + timeTaken + " ms");
        if (statisticsAgentSubSystem != null && statisticsAgentSubSystem.isActive()) {
          storeCacheObjectsEvictedStats(evictedCount, currentCount, newObjectsCount, timeTaken);
        }
      }
    }

    private synchronized void storeCacheObjectsEvictedStats(int evictedCount, int currentCount, int newObjectsCount, long timeTaken) {
      Date moment = new Date();
      AgentStatisticsManager agentStatisticsManager = statisticsAgentSubSystem.getStatisticsManager();
      Collection sessions = agentStatisticsManager.getActiveSessionIDsForAction(CACHE_OBJECTS_EVICTED);
      if (sessions != null && sessions.size() > 0) {
        StatisticData[] datas = getCacheObjectsEvictedData(evictedCount, currentCount, newObjectsCount, timeTaken);
        storeStatisticsDatas(moment, sessions, datas);
      }
    }

    private synchronized void storeStatisticsDatas(Date moment, Collection sessions, StatisticData[] datas) {
      try {
        for (Iterator sessionsIterator = sessions.iterator(); sessionsIterator.hasNext();) {
          String session = (String)sessionsIterator.next();
          for (int i = 0; i < datas.length; i++) {
            StatisticData data = datas[i];
            statisticsAgentSubSystem.getStatisticsManager().injectStatisticData(session, data.moment(moment));
          }
        }
      } catch (TCAgentStatisticsManagerException e) {
        logger.error("Unexpected error while trying to store Cache Objects Evict Request statistics statistics.", e);
      }
    }

    private synchronized StatisticData[] getCacheObjectsEvictedData(int evictedCount, int currentCount, int newObjectsCount, long timeTaken) {
      List datas = new ArrayList();
      StatisticData statisticData = new StatisticData(CACHE_OBJECTS_EVICTED, "evicted count", new Long(evictedCount));
      datas.add(statisticData);

      statisticData = new StatisticData(CACHE_OBJECTS_EVICTED, "current count", new Long(currentCount));
      datas.add(statisticData);

      statisticData = new StatisticData(CACHE_OBJECTS_EVICTED, "new objects count", new Long(newObjectsCount));
      datas.add(statisticData);

      statisticData = new StatisticData(CACHE_OBJECTS_EVICTED, "time taken", new Long(timeTaken));
      datas.add(statisticData);

      return (StatisticData[])datas.toArray(new StatisticData[0]);
    }

    private int getNewObjectsCount() {
      return countAfter - (countBefore - evicted);
    }

    // TODO:: This need to be more intellegent. It should also check if a GC actually happened after eviction. Use
    // Reference Queue
    private int computeObjects2Evict(int currentCount) {
      if (type == MemoryEventType.BELOW_THRESHOLD || calculatedCacheSize > currentCount) { return 0; }
      int overshoot = 0;
      if (config.getObjectCountCriticalThreshold() > 0 && currentCount > config.getObjectCountCriticalThreshold()) {
        // Give higher precidence to Object Count Critical Threshold than calculate cache size.
        overshoot = currentCount - config.getObjectCountCriticalThreshold();
      } else {
        overshoot = currentCount - calculatedCacheSize;
      }
      if (overshoot <= 0) { return 0; }
      int objects2Evict = overshoot + ((calculatedCacheSize * config.getPercentageToEvict()) / 100);
      // it is possible for higher percentage to evict, the calculated value crosses the current count limit : CDV-592
      if (objects2Evict > currentCount) objects2Evict = currentCount;
      return objects2Evict;
    }

    public String toString() {
      return "CacheStats[ type = " + type + ",\n\t usage = " + usage + ",\n\t countBefore = " + countBefore
             + ", toEvict = " + toEvict + ", evicted = " + evicted + ", countAfter = " + countAfter
             + ", objectsGCed = " + objectsGCed + ",\n\t state = " + state + "]";
    }
  }

}
