/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.impl;

import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.PostInit;
import com.tc.async.api.Sink;
import com.tc.async.api.StageManager;
import com.tc.config.HaConfig;
import com.tc.config.schema.setup.L2TVSConfigurationSetupManager;
import com.tc.l2.api.L2Coordinator;
import com.tc.l2.ha.L2HACoordinator;
import com.tc.l2.ha.WeightGeneratorFactory;
import com.tc.logging.DumpHandlerStore;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.L2Management;
import com.tc.management.beans.LockStatisticsMonitor;
import com.tc.management.beans.TCServerInfoMBean;
import com.tc.management.beans.object.ServerDBBackupMBean;
import com.tc.net.GroupID;
import com.tc.net.ServerID;
import com.tc.net.groups.GroupManager;
import com.tc.net.groups.SingleNodeGroupManager;
import com.tc.net.groups.StripeIDStateManager;
import com.tc.net.groups.TCGroupManagerImpl;
import com.tc.net.protocol.tcm.ChannelManager;
import com.tc.net.protocol.transport.ConnectionIDFactory;
import com.tc.object.config.schema.NewL2DSOConfig;
import com.tc.object.msg.MessageRecycler;
import com.tc.object.net.ChannelStatsImpl;
import com.tc.object.net.DSOChannelManager;
import com.tc.object.persistence.api.PersistentMapStore;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.api.ObjectRequestManager;
import com.tc.objectserver.api.ServerMapRequestManager;
import com.tc.objectserver.clustermetadata.ServerClusterMetaDataManager;
import com.tc.objectserver.core.api.DSOGlobalServerStats;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.core.impl.ServerConfigurationContextImpl;
import com.tc.objectserver.dgc.api.GarbageCollectionInfoPublisher;
import com.tc.objectserver.dgc.api.GarbageCollector;
import com.tc.objectserver.dgc.impl.DGCOperatorEventPublisher;
import com.tc.objectserver.dgc.impl.GCStatsEventPublisher;
import com.tc.objectserver.dgc.impl.MarkAndSweepGarbageCollector;
import com.tc.objectserver.gtx.ServerGlobalTransactionManager;
import com.tc.objectserver.handshakemanager.ServerClientHandshakeManager;
import com.tc.objectserver.l1.api.ClientStateManager;
import com.tc.objectserver.locks.LockManager;
import com.tc.objectserver.mgmt.ObjectStatsRecorder;
import com.tc.objectserver.persistence.api.ManagedObjectStore;
import com.tc.objectserver.storage.api.DBEnvironment;
import com.tc.objectserver.storage.api.DBFactory;
import com.tc.objectserver.storage.berkeleydb.BerkeleyDBFactory;
import com.tc.objectserver.tx.CommitTransactionMessageToTransactionBatchReader;
import com.tc.objectserver.tx.PassThruTransactionFilter;
import com.tc.objectserver.tx.ServerTransactionManager;
import com.tc.objectserver.tx.TransactionBatchManagerImpl;
import com.tc.objectserver.tx.TransactionFilter;
import com.tc.objectserver.tx.TransactionalObjectManager;
import com.tc.operatorevent.TerracottaOperatorEventHistoryProvider;
import com.tc.properties.TCProperties;
import com.tc.server.ServerConnectionValidator;
import com.tc.statistics.StatisticsAgentSubSystem;
import com.tc.statistics.StatisticsAgentSubSystemImpl;
import com.tc.statistics.beans.impl.StatisticsGatewayMBeanImpl;
import com.tc.statistics.retrieval.StatisticsRetrievalRegistry;
import com.tc.stats.counter.sampled.SampledCounter;
import com.tc.util.runtime.ThreadDumpUtil;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Properties;

import javax.management.MBeanServer;

public class StandardDSOServerBuilder implements DSOServerBuilder {
  private final HaConfig   haConfig;
  private final GroupID    thisGroupID;
  protected final TCLogger logger;

  public StandardDSOServerBuilder(HaConfig haConfig, TCLogger logger) {
    this.logger = logger;
    this.logger.info("Standard DSO Server created");
    this.haConfig = haConfig;
    this.thisGroupID = this.haConfig.getThisGroupID();
  }

  public GarbageCollector createGarbageCollector(List<PostInit> toInit, ObjectManagerConfig objectManagerConfig,
                                                 ObjectManager objectMgr, ClientStateManager stateManager,
                                                 StageManager stageManager, int maxStageSize,
                                                 GarbageCollectionInfoPublisher gcPublisher,
                                                 ObjectManager objectManager, ClientStateManager clientStateManger,
                                                 GCStatsEventPublisher gcEventListener,
                                                 StatisticsAgentSubSystem statsAgentSubSystem) {
    MarkAndSweepGarbageCollector gc = new MarkAndSweepGarbageCollector(objectManagerConfig, objectMgr, stateManager,
                                                                       gcPublisher);
    gc.addListener(gcEventListener);
    gc.addListener(new DGCOperatorEventPublisher());
    return gc;
  }

  public GroupManager createGroupCommManager(boolean networkedHA, L2TVSConfigurationSetupManager configManager,
                                             StageManager stageManager, ServerID serverNodeID, Sink httpSink,
                                             StripeIDStateManager stripeStateManager,
                                             ServerGlobalTransactionManager gtxm) {
    if (networkedHA) {
      return new TCGroupManagerImpl(configManager, stageManager, serverNodeID, httpSink, this.haConfig.getNodesStore());
    } else {
      return new SingleNodeGroupManager();
    }
  }

  public ObjectRequestManager createObjectRequestManager(ObjectManager objectMgr, DSOChannelManager channelManager,
                                                         ClientStateManager clientStateMgr,
                                                         ServerTransactionManager transactionMgr,
                                                         Sink objectRequestSink, Sink respondObjectRequestSink,
                                                         ObjectStatsRecorder statsRecorder, List<PostInit> toInit,
                                                         StageManager stageManager, int maxStageSize,
                                                         DumpHandlerStore dumpHandlerStore) {
    ObjectRequestManagerImpl orm = new ObjectRequestManagerImpl(objectMgr, channelManager, clientStateMgr,
                                                                objectRequestSink, respondObjectRequestSink,
                                                                statsRecorder);
    return new ObjectRequestManagerRestartImpl(objectMgr, transactionMgr, orm);
  }

  public ServerMapRequestManager createServerMapRequestManager(ObjectManager objectMgr,
                                                               DSOChannelManager channelManager,
                                                               Sink respondToServerTCMapSink,
                                                               Sink managedObjectRequestSink) {
    return new ServerMapRequestManagerImpl(objectMgr, channelManager, respondToServerTCMapSink,
                                           managedObjectRequestSink);
  }

  public ServerConfigurationContext createServerConfigurationContext(
                                                                     StageManager stageManager,
                                                                     ObjectManager objMgr,
                                                                     ObjectRequestManager objRequestMgr,
                                                                     ServerMapRequestManager serverTCMapRequestManager,
                                                                     ManagedObjectStore objStore,
                                                                     LockManager lockMgr,
                                                                     DSOChannelManager channelManager,
                                                                     ClientStateManager clientStateMgr,
                                                                     ServerTransactionManager txnMgr,
                                                                     TransactionalObjectManager txnObjectMgr,
                                                                     ChannelStatsImpl channelStats,
                                                                     L2Coordinator coordinator,
                                                                     TransactionBatchManagerImpl transactionBatchManager,
                                                                     ServerGlobalTransactionManager gtxm,
                                                                     ServerClientHandshakeManager clientHandshakeManager,
                                                                     ServerClusterMetaDataManager clusterMetaDataManager,
                                                                     DSOGlobalServerStats serverStats,
                                                                     ConnectionIDFactory connectionIdFactory,
                                                                     int maxStageSize,
                                                                     ChannelManager genericChannelManager,
                                                                     DumpHandlerStore dumpHandlerStore) {
    return new ServerConfigurationContextImpl(stageManager, objMgr, objRequestMgr, serverTCMapRequestManager, objStore,
                                              lockMgr, channelManager, clientStateMgr, txnMgr, txnObjectMgr,
                                              clientHandshakeManager, channelStats, coordinator,
                                              new CommitTransactionMessageToTransactionBatchReader(serverStats),
                                              transactionBatchManager, gtxm, clusterMetaDataManager);
  }

  public TransactionFilter getTransactionFilter(List<PostInit> toInit, StageManager stageManager, int maxStageSize) {
    PassThruTransactionFilter txnFilter = new PassThruTransactionFilter();
    toInit.add(txnFilter);
    return txnFilter;
  }

  public void populateAdditionalStatisticsRetrivalRegistry(StatisticsRetrievalRegistry registry) {
    // Add any additional Statistics here
  }

  public GroupManager getClusterGroupCommManager() {
    throw new AssertionError("Not supported");
  }

  public GCStatsEventPublisher getLocalDGCStatsEventPublisher() {
    throw new AssertionError("Not supported");
  }

  public void dump() {
    TCLogging.getDumpLogger().info(ThreadDumpUtil.getThreadDump());
  }

  public void initializeContext(ConfigurationContext context) {
    // Nothing to initialize here
  }

  public L2Coordinator createL2HACoordinator(TCLogger consoleLogger, DistributedObjectServer server,
                                             StageManager stageManager, GroupManager groupCommsManager,
                                             PersistentMapStore persistentMapStore, ObjectManager objectManager,
                                             ServerTransactionManager transactionManager,
                                             ServerGlobalTransactionManager gtxm,
                                             WeightGeneratorFactory weightGeneratorFactory,
                                             L2TVSConfigurationSetupManager configurationSetupManager,
                                             MessageRecycler recycler, StripeIDStateManager stripeStateManager) {
    return new L2HACoordinator(consoleLogger, server, stageManager, groupCommsManager, persistentMapStore,
                               objectManager, transactionManager, gtxm, weightGeneratorFactory,
                               configurationSetupManager, recycler, thisGroupID, stripeStateManager);
  }

  public L2Management createL2Management(TCServerInfoMBean tcServerInfoMBean,
                                         LockStatisticsMonitor lockStatisticsMBean,
                                         StatisticsAgentSubSystemImpl statisticsAgentSubSystem,
                                         StatisticsGatewayMBeanImpl statisticsGateway,
                                         L2TVSConfigurationSetupManager configSetupManager,
                                         DistributedObjectServer distributedObjectServer, InetAddress bind,
                                         int jmxPort, Sink remoteEventsSink,
                                         ServerConnectionValidator serverConnectionValidator,
                                         ServerDBBackupMBean serverDBBackupMBean) throws Exception {
    return new L2Management(tcServerInfoMBean, lockStatisticsMBean, statisticsAgentSubSystem, statisticsGateway,
                            configSetupManager, distributedObjectServer, bind, jmxPort, remoteEventsSink);
  }

  public void registerForOperatorEvents(L2Management l2Management,
                                        TerracottaOperatorEventHistoryProvider operatorEventHistoryProvider,
                                        MBeanServer l2MbeanServer) {
    // NOP
  }

  public DBEnvironment createDBEnvironment(final boolean persistent, final File dbhome,
                                           final TCProperties l2Properties, final NewL2DSOConfig l2DSOCofig,
                                           DumpHandlerStore dumpHandlerStore, final StageManager stageManager,
                                           final SampledCounter l2FaultFromDisk, SampledCounter l2FaultFromOffheap,
                                           SampledCounter l2FlushFromOffheap) throws IOException {
    // XXX: one day DB selection will be from tc.props
    final DBFactory dbFactory = new BerkeleyDBFactory(l2Properties.getPropertiesFor("berkeleydb")
        .addAllPropertiesTo(new Properties()));
    return dbFactory.createEnvironment(persistent, dbhome, l2FaultFromDisk);
  }
}
