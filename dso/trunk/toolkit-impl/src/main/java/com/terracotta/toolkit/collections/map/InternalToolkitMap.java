/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.collections.map;

import org.terracotta.toolkit.concurrent.locks.ToolkitReadWriteLock;
import org.terracotta.toolkit.internal.concurrent.locks.ToolkitLockTypeInternal;
import org.terracotta.toolkit.internal.store.ConfigFieldsInternal.LOCK_STRATEGY;
import org.terracotta.toolkit.search.attribute.ToolkitAttributeExtractor;

import com.tc.object.bytecode.TCServerMap;
import com.tc.object.servermap.localcache.L1ServerMapLocalCacheStore;
import com.tc.object.servermap.localcache.PinnedEntryFaultCallback;
import com.terracotta.toolkit.collections.map.ServerMap.GetType;
import com.terracotta.toolkit.object.TCToolkitObject;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

public interface InternalToolkitMap<K, V> extends ConcurrentMap<K, V>, TCServerMap, TCToolkitObject,
    ValuesResolver<K, V> {

  String getName();

  ToolkitLockTypeInternal getLockType();

  boolean isEventual();

  boolean invalidateOnChange();

  boolean isLocalCacheEnabled();

  int getMaxTTISeconds();

  int getMaxTTLSeconds();

  int getMaxCountInCluster();

  V put(K key, V value, int createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds);

  void putVersioned(K key, V value, long version, int createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds);

  void putNoReturn(K key, V value, int createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds);

  V putIfAbsent(K key, V value, int createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds);

  V get(Object key, boolean quiet);

  void setConfigField(String name, Object value);

  void initializeLocalCache(L1ServerMapLocalCacheStore<K, V> localCacheStore, PinnedEntryFaultCallback callback,
                            boolean localCacheEnabled);

  void removeNoReturn(Object key);

  void removeNoReturnVersioned(Object key, long version);

  V unsafeLocalGet(Object key);

  V unlockedGet(K key, boolean quiet);

  int localSize();

  Set<K> localKeySet();

  boolean containsLocalKey(Object key);

  V checkAndGetNonExpiredValue(K key, Object value, GetType getType, boolean quiet);

  void clearLocalCache();

  void cleanLocalState();

  long localOnHeapSizeInBytes();

  long localOffHeapSizeInBytes();

  int localOnHeapSize();

  int localOffHeapSize();

  boolean containsKeyLocalOnHeap(Object key);

  boolean containsKeyLocalOffHeap(Object key);

  void unlockedPutNoReturn(K key, V value, int createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds);

  void unlockedPutNoReturnVersioned(K key, V value, long version, int createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds);

  void unlockedRemoveNoReturn(Object key);

  void unlockedRemoveNoReturnVersioned(Object key, long version);

  void unlockedClear();

  boolean isCompressionEnabled();

  boolean isCopyOnReadEnabled();

  void disposeLocally();

  ToolkitReadWriteLock createLockForKey(K key);

  void registerAttributeExtractor(ToolkitAttributeExtractor extractor);

  boolean isEvictionEnabled();

  void setConfigFieldInternal(String fieldChanged, Object changedValue);

  void setLockStrategy(LOCK_STRATEGY strategy);

  void addTxnInProgressKeys(Set<K> txnInProgressForAdd, Set<K> removeSet);

  Set<K> keySet(Set<K> filterSet);

  Collection<V> values(Set<K> filterSet);

  Set<Entry<K, V>> entrySet(Set<K> filterSet);
}
