/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.retrieval.actions;

import com.tc.async.api.Stage;
import com.tc.async.api.StageManager;
import com.tc.async.api.StageQueueStats;
import com.tc.statistics.DynamicSRA;
import com.tc.statistics.StatisticData;
import com.tc.statistics.StatisticType;
import com.tc.stats.Stats;
import com.tc.util.Assert;

import java.util.Collection;
import java.util.Iterator;

public class SRAStageQueueDepths implements DynamicSRA {

  public static final String ACTION_NAME = "stage queue depth";

  private final StageManager stageManager;

  public SRAStageQueueDepths(final StageManager stageManager) {
    Assert.assertNotNull(stageManager);
    this.stageManager = stageManager;
    disableStatisticCollection();
  }

  public String getName() {
    return ACTION_NAME;
  }

  public StatisticType getType() {
    return StatisticType.SNAPSHOT;
  }

  public StatisticData[] retrieveStatisticData() {
    if (!isStatisticsCollectionEnabled()) {
      return EMPTY_STATISTIC_DATA;
    }
    Stats[] stats = stageManager.getStats();
    StatisticData[] data = new StatisticData[stats.length];
    for (int i = 0; i < stats.length; i++) {
      StageQueueStats stageStat = (StageQueueStats)stats[i];
      data[i] = new StatisticData(ACTION_NAME, stageStat.getName(), new Long(stageStat.getDepth()));
    }
    return data;
  }

  private boolean isStatisticsCollectionEnabled() {
    synchronized (stageManager) {
      Collection stages = stageManager.getStages();
      for (Iterator it = stages.iterator(); it.hasNext();) {
        if (!((Stage)it.next()).getSink().isStatsCollectionEnabled()) {
          return false;
        }
      }
    }

    return true;
  }

  public void enableStatisticCollection() {
    synchronized (stageManager) {
      Collection stages = stageManager.getStages();
      for (Iterator it = stages.iterator(); it.hasNext();) {
        ((Stage)it.next()).getSink().enableStatsCollection(true);
      }
    }
  }

  public void disableStatisticCollection() {
    synchronized (stageManager) {
      Collection stages = stageManager.getStages();
      for (Iterator it = stages.iterator(); it.hasNext();) {
        ((Stage)it.next()).getSink().enableStatsCollection(false);
      }
    }
  }
}
