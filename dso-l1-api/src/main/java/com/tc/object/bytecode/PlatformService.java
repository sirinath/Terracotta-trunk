/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.object.bytecode;

import com.tc.cluster.DsoCluster;
import com.tc.logging.TCLogger;
import com.tc.net.GroupID;
import com.tc.object.ObjectID;
import com.tc.object.TCObject;
import com.tc.object.locks.LockLevel;
import com.tc.object.metadata.MetaDataDescriptor;
import com.tc.object.tx.TransactionCompleteListener;
import com.tc.operatorevent.TerracottaOperatorEvent.EventSubsystem;
import com.tc.operatorevent.TerracottaOperatorEvent.EventType;
import com.tc.properties.TCProperties;
import com.tc.search.SearchQueryResults;
import com.tcclient.cluster.DsoNode;
import com.terracottatech.search.NVPair;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public interface PlatformService {

  <T> T lookupRegisteredObjectByName(String name, Class<T> expectedType);

  <T> T registerObjectByNameIfAbsent(String name, T object);

  void logicalInvoke(final Object object, final String methodName, final Object[] params);

  void waitForAllCurrentTransactionsToComplete();

  boolean isHeldByCurrentThread(Object lockID, LockLevel level);

  void beginLock(final Object lockID, final LockLevel level);

  void beginLockInterruptibly(Object obj, LockLevel level) throws InterruptedException;

  void commitLock(final Object lockID, final LockLevel level);

  boolean tryBeginLock(Object lockID, LockLevel level);

  public boolean tryBeginLock(final Object lockID, final LockLevel level, final long timeout, TimeUnit timeUnit)
      throws InterruptedException;

  void lockIDWait(Object lockID, long timeout, TimeUnit timeUnit) throws InterruptedException;

  void lockIDNotify(Object lockID);

  void lockIDNotifyAll(Object lockID);

  TCProperties getTCProperties();

  Object lookupRoot(final String name, GroupID gid);

  Object lookupOrCreateRoot(final String name, final Object object, GroupID gid);

  TCObject lookupOrCreate(final Object obj, GroupID gid);

  Object lookupObject(final ObjectID id);

  GroupID[] getGroupIDs();

  TCLogger getLogger(final String loggerName);

  void addTransactionCompleteListener(TransactionCompleteListener listener);

  MetaDataDescriptor createMetaDataDescriptor(String category);

  void fireOperatorEvent(EventType coreOperatorEventLevel, EventSubsystem coreEventSubsytem, String eventMessage);

  DsoNode getCurrentNode();

  DsoCluster getDsoCluster();

  void registerBeforeShutdownHook(Runnable hook);

  String getUUID();

  SearchQueryResults executeQuery(String cachename, List queryStack, boolean includeKeys, boolean includeValues,
                                  Set<String> attributeSet, List<NVPair> sortAttributes, List<NVPair> aggregators,
                                  int maxResults, int batchSize, boolean waitForTxn);

  SearchQueryResults executeQuery(String cachename, List queryStack, Set<String> attributeSet,
                                  Set<String> groupByAttributes, List<NVPair> sortAttributes, List<NVPair> aggregators,
                                  int maxResults, int batchSize, boolean waitForTxn);

  void preFetchObject(final ObjectID id);

  void verifyCapability(String capability);


}