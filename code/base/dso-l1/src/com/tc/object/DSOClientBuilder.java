/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.object;

import com.tc.async.api.Sink;
import com.tc.logging.ClientIDLogger;
import com.tc.logging.TCLogger;
import com.tc.management.ClientLockStatManager;
import com.tc.management.TCClient;
import com.tc.management.remote.protocol.terracotta.TunnelingEventHandler;
import com.tc.net.protocol.NetworkStackHarnessFactory;
import com.tc.net.protocol.tcm.ClientMessageChannel;
import com.tc.net.protocol.tcm.CommunicationsManager;
import com.tc.net.protocol.tcm.MessageMonitor;
import com.tc.net.protocol.transport.ConnectionPolicy;
import com.tc.net.protocol.transport.HealthCheckerConfig;
import com.tc.object.bytecode.hook.impl.PreparedComponentsFromL2Connection;
import com.tc.object.cache.ClockEvictionPolicy;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.dna.api.DNAEncoding;
import com.tc.object.gtx.ClientGlobalTransactionManager;
import com.tc.object.handshakemanager.ClientHandshakeCallback;
import com.tc.object.handshakemanager.ClientHandshakeManager;
import com.tc.object.idprovider.api.ObjectIDProvider;
import com.tc.object.idprovider.impl.ObjectIDClientHandshakeRequester;
import com.tc.object.idprovider.impl.RemoteObjectIDBatchSequenceProvider;
import com.tc.object.loaders.ClassProvider;
import com.tc.object.locks.ClientLockManager;
import com.tc.object.locks.ClientLockManagerConfig;
import com.tc.object.logging.RuntimeLogger;
import com.tc.object.msg.ClientHandshakeMessageFactory;
import com.tc.object.msg.KeysForOrphanedValuesMessageFactory;
import com.tc.object.msg.LockRequestMessageFactory;
import com.tc.object.msg.NodeMetaDataMessageFactory;
import com.tc.object.msg.NodesWithObjectsMessageFactory;
import com.tc.object.net.DSOClientMessageChannel;
import com.tc.object.session.SessionManager;
import com.tc.object.session.SessionProvider;
import com.tc.object.tx.RemoteTransactionManager;
import com.tc.object.tx.TransactionIDGenerator;
import com.tc.object.tx.TransactionBatchWriter.FoldingConfig;
import com.tc.stats.counter.Counter;
import com.tc.stats.counter.sampled.derived.SampledRateCounter;
import com.tc.util.ToggleableReferenceManager;
import com.tc.util.UUID;
import com.tc.util.runtime.ThreadIDManager;
import com.tc.util.sequence.BatchSequence;
import com.tc.util.sequence.BatchSequenceReceiver;
import com.tcclient.cluster.DsoClusterInternal;

import java.util.Collection;

public interface DSOClientBuilder {

  DSOClientMessageChannel createDSOClientMessageChannel(final CommunicationsManager commMgr,
                                                        final PreparedComponentsFromL2Connection connComp,
                                                        final SessionProvider sessionProvider, int maxReconnectTries,
                                                        int socketConnectTimeout, TCClient client);

  CommunicationsManager createCommunicationsManager(final MessageMonitor monitor,
                                                    final NetworkStackHarnessFactory stackHarnessFactory,
                                                    final ConnectionPolicy connectionPolicy, int workerCommThreads,
                                                    final HealthCheckerConfig hcConfig);

  TunnelingEventHandler createTunnelingEventHandler(final ClientMessageChannel ch, UUID id);

  ClientGlobalTransactionManager createClientGlobalTransactionManager(final RemoteTransactionManager remoteTxnMgr);

  RemoteObjectManager createRemoteObjectManager(final TCLogger logger, final DSOClientMessageChannel dsoChannel,
                                                final int faultCount, final SessionManager sessionManager);

  ClusterMetaDataManager createClusterMetaDataManager(final DSOClientMessageChannel dsoChannel,
                                                      final DNAEncoding encoding,
                                                      final ThreadIDManager threadIDManager,
                                                      final NodesWithObjectsMessageFactory nwoFactory,
                                                      final KeysForOrphanedValuesMessageFactory kfovFactory,
                                                      final NodeMetaDataMessageFactory nmdmFactory);

  ClientObjectManagerImpl createObjectManager(final RemoteObjectManager remoteObjectManager,
                                              final DSOClientConfigHelper dsoConfig, final ObjectIDProvider idProvider,
                                              final ClockEvictionPolicy clockEvictionPolicy,
                                              final RuntimeLogger rtLogger, final ClientIDProvider clientIDProvider,
                                              final ClassProvider classProviderLocal,
                                              final TCClassFactory classFactory, final TCObjectFactory objectFactory,
                                              final Portability portability, final DSOClientMessageChannel dsoChannel,
                                              final ToggleableReferenceManager toggleRefMgr);

  ClientLockManager createLockManager(final DSOClientMessageChannel dsoChannel, final ClientIDLogger clientIDLogger,
                                      final SessionManager sessionManager, final ClientLockStatManager lockStatManager,
                                      final LockRequestMessageFactory lockRequestMessageFactory,
                                      final ThreadIDManager threadManager,
                                      final ClientGlobalTransactionManager clientGlobalTransactionManager,
                                      final ClientLockManagerConfig clientLockManagerConfig);

  @Deprecated
  ClientLockStatManager createLockStatsManager();

  RemoteTransactionManager createRemoteTransactionManager(final ClientIDProvider cidProvider,
                                                          final DNAEncoding encoding,
                                                          final FoldingConfig foldingConfig,
                                                          final TransactionIDGenerator transactionIDGenerator,
                                                          final SessionManager sessionManager,
                                                          final DSOClientMessageChannel dsoChannel,
                                                          final Counter outstandingBatchesCounter,
                                                          final Counter pendingBatchesSize,
                                                          SampledRateCounter transactionSizeCounter,
                                                          SampledRateCounter transactionPerBatchCounter);

  ObjectIDClientHandshakeRequester getObjectIDClientHandshakeRequester(final BatchSequenceReceiver sequence);

  BatchSequence[] createSequences(RemoteObjectIDBatchSequenceProvider remoteIDProvider, int requestSize);

  ObjectIDProvider createObjectIdProvider(BatchSequence[] sequences, ClientIDProvider clientIDProvider);

  BatchSequenceReceiver getBatchReceiver(BatchSequence[] sequences);

  ClientHandshakeManager createClientHandshakeManager(final TCLogger logger, final DSOClientMessageChannel channel,
                                                      final ClientHandshakeMessageFactory chmf, final Sink pauseSink,
                                                      final SessionManager sessionManager,
                                                      final DsoClusterInternal dsoCluster, final String clientVersion,
                                                      final Collection<ClientHandshakeCallback> callbacks);

}
