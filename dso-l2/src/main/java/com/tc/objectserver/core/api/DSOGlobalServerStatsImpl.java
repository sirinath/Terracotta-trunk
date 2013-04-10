/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.core.api;

import com.tc.objectserver.api.ObjectManagerStats;
import com.tc.objectserver.impl.ObjectManagerStatsImpl;
import com.tc.stats.counter.sampled.SampledCounter;
import com.tc.stats.counter.sampled.SampledCumulativeCounter;
import com.tc.stats.counter.sampled.derived.SampledRateCounter;

public class DSOGlobalServerStatsImpl implements DSOGlobalServerStats {

  private final SampledCounter readCounter;
  private final SampledCounter           txnCounter;
  private final ObjectManagerStatsImpl   objMgrStats;
  private final SampledCounter       evictionRateCounter;
  private final SampledCounter       expirationRateCounter;

  private final SampledCounter           broadcastCounter;
  private final SampledCounter           globalLockCounter;
  private final SampledCounter           globalLockRecallCounter;
  private final SampledRateCounter       changesPerBroadcast;
  private final SampledRateCounter       transactionSizeCounter;
  private SampledCounter                 operationCounter;

  private SampledCumulativeCounter serverMapGetSizeRequestsCounter;
  private SampledCumulativeCounter serverMapGetValueRequestsCounter;
  private SampledCumulativeCounter serverMapGetSnapshotRequestsCounter;

  public DSOGlobalServerStatsImpl(SampledCounter readCounter, SampledCounter txnCounter,
                                  ObjectManagerStatsImpl objMgrStats, SampledCounter broadcastCounter,
                                  SampledCounter globalLockRecallCounter,
                                  SampledRateCounter changesPerBroadcast, SampledRateCounter transactionSizeCounter,
                                  SampledCounter globalLockCounter, SampledCounter evictionRateCounter,
                                  SampledCounter expirationRateCounter) {
    this.readCounter = readCounter;
    this.txnCounter = txnCounter;
    this.objMgrStats = objMgrStats;
    this.evictionRateCounter = evictionRateCounter;
    this.expirationRateCounter = expirationRateCounter;
    this.broadcastCounter = broadcastCounter;
    this.globalLockRecallCounter = globalLockRecallCounter;
    this.changesPerBroadcast = changesPerBroadcast;
    this.transactionSizeCounter = transactionSizeCounter;
    this.globalLockCounter = globalLockCounter;
  }
  
  public DSOGlobalServerStatsImpl(SampledCounter readCounter, SampledCounter txnCounter,
                                  ObjectManagerStatsImpl objMgrStats, SampledCounter broadcastCounter,
                                  SampledCounter globalLockRecallCounter,
                                  SampledRateCounter changesPerBroadcast, SampledRateCounter transactionSizeCounter,
                                  SampledCounter globalLockCounter, SampledCounter evictionRateCounter,
                                  SampledCounter expirationRateCounter, SampledCounter operationCounter) {
    this(readCounter, txnCounter, objMgrStats, broadcastCounter, globalLockRecallCounter, changesPerBroadcast,
        transactionSizeCounter, globalLockCounter, evictionRateCounter, expirationRateCounter);
    this.operationCounter = operationCounter;
  }

  public DSOGlobalServerStatsImpl serverMapGetSizeRequestsCounter(final SampledCumulativeCounter counter) {
    this.serverMapGetSizeRequestsCounter = counter;
    return this;
  }
  
  public DSOGlobalServerStatsImpl serverMapGetValueRequestsCounter(final SampledCumulativeCounter counter) {
    this.serverMapGetValueRequestsCounter = counter;
    return this;
  }
  public DSOGlobalServerStatsImpl serverMapGetSnapshotRequestsCounter(final SampledCumulativeCounter counter) {
    this.serverMapGetSnapshotRequestsCounter = counter;
    return this;
  }

  @Override
  public SampledCounter getReadOperationRateCounter() {
    return this.readCounter;
  }

  @Override
  public ObjectManagerStats getObjectManagerStats() {
    return this.objMgrStats;
  }

  @Override
  public SampledCounter getTransactionCounter() {
    return this.txnCounter;
  }

  @Override
  public SampledCounter getBroadcastCounter() {
    return broadcastCounter;
  }

  @Override
  public SampledCounter getGlobalLockRecallCounter() {
    return globalLockRecallCounter;
  }

  @Override
  public SampledRateCounter getChangesPerBroadcastCounter() {
    return changesPerBroadcast;
  }

  @Override
  public SampledRateCounter getTransactionSizeCounter() {
    return transactionSizeCounter;
  }

  @Override
  public SampledCounter getGlobalLockCounter() {
    return this.globalLockCounter;
  }

  @Override
  public SampledCounter getOperationCounter() {
    return operationCounter;
  }

  @Override
  public SampledCumulativeCounter getServerMapGetSizeRequestsCounter() {
    return serverMapGetSizeRequestsCounter;
  }

  @Override
  public SampledCumulativeCounter getServerMapGetValueRequestsCounter() {
    return serverMapGetValueRequestsCounter;
  }
  
  public SampledCumulativeCounter getServerMapGetSnapshotRequestsCounter() {
    return serverMapGetSnapshotRequestsCounter;
  }

  @Override
  public SampledCounter getEvictionRateCounter() {
    return evictionRateCounter;
  }

  @Override
  public SampledCounter getExpirationRateCounter() {
    return expirationRateCounter;
  }
}
