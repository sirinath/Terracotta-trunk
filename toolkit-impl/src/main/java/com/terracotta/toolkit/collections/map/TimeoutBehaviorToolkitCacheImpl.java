/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.collections.map;

import org.terracotta.toolkit.cache.ToolkitCacheListener;
import org.terracotta.toolkit.cluster.ClusterNode;
import org.terracotta.toolkit.concurrent.locks.ToolkitReadWriteLock;
import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.internal.cache.ToolkitCacheInternal;
import org.terracotta.toolkit.search.QueryBuilder;
import org.terracotta.toolkit.search.attribute.ToolkitAttributeExtractor;

import com.tc.object.ObjectID;
import com.terracotta.toolkit.object.DestroyableToolkitObject;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TimeoutBehaviorToolkitCacheImpl<K, V> implements ValuesResolver<K, V>, ToolkitCacheInternal<K, V>,
    DestroyableToolkitObject {
  private final ToolkitCacheInternal<K, V> mutationBehaviourResolver;
  private final ToolkitCacheInternal<K, V> immutationBehaviourResolver;

  public TimeoutBehaviorToolkitCacheImpl(ToolkitCacheInternal<K, V> immutationBehaviourResolver,
                                         ToolkitCacheInternal<K, V> mutationBehaviourResolver) {
    this.immutationBehaviourResolver = immutationBehaviourResolver;
    this.mutationBehaviourResolver = mutationBehaviourResolver;
  }

  @Override
  public String getName() {
    return immutationBehaviourResolver.getName();
  }

  @Override
  public boolean isDestroyed() {
    return immutationBehaviourResolver.isDestroyed();
  }

  @Override
  public void destroy() {
    mutationBehaviourResolver.destroy();
  }

  @Override
  public V getQuiet(Object key) {
    return immutationBehaviourResolver.unsafeLocalGet(key);
  }

  @Override
  public Map<K, V> getAllQuiet(Collection<K> keys) {
    Map<K, V> rv = new HashMap<K, V>();
    for (K key : keys) {
      rv.put(key, getQuiet(key));
    }
    return rv;
  }

  @Override
  public void putNoReturn(K key, V value, long createTimeInSecs, int maxTTISeconds, int maxTTLSeconds) {
    mutationBehaviourResolver.putNoReturn(key, value, createTimeInSecs, maxTTISeconds, maxTTLSeconds);

  }

  @Override
  public V putIfAbsent(K key, V value, long createTimeInSecs, int maxTTISeconds, int maxTTLSeconds) {
    return mutationBehaviourResolver.putIfAbsent(key, value, createTimeInSecs, maxTTISeconds, maxTTLSeconds);
  }

  @Override
  public void addListener(ToolkitCacheListener<K> listener) {
    mutationBehaviourResolver.addListener(listener);
  }

  @Override
  public void removeListener(ToolkitCacheListener<K> listener) {
    mutationBehaviourResolver.removeListener(listener);
  }

  @Override
  public void unpinAll() {
    mutationBehaviourResolver.unpinAll();
  }

  @Override
  public boolean isPinned(K key) {
    return immutationBehaviourResolver.isPinned(key);
  }

  @Override
  public void setPinned(K key, boolean pinned) {
    mutationBehaviourResolver.setPinned(key, pinned);
  }

  @Override
  public void removeNoReturn(Object key) {
    mutationBehaviourResolver.removeNoReturn(key);
  }

  @Override
  public void putNoReturn(K key, V value) {
    mutationBehaviourResolver.putNoReturn(key, value);
  }

  @Override
  public Map<K, V> getAll(Collection<? extends K> keys) {
    return getAllQuiet((Collection<K>) keys);
  }

  @Override
  public Configuration getConfiguration() {
    return immutationBehaviourResolver.getConfiguration();
  }

  @Override
  public void setConfigField(String name, Serializable value) {
    mutationBehaviourResolver.setConfigField(name, value);

  }

  @Override
  public boolean containsValue(Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ToolkitReadWriteLock createLockForKey(K key) {
    // TODO: return nonstop lock when supporting nonstop for locks.
    return immutationBehaviourResolver.createLockForKey(key);
  }

  @Override
  public void setAttributeExtractor(ToolkitAttributeExtractor attrExtractor) {
    immutationBehaviourResolver.setAttributeExtractor(attrExtractor);
  }

  @Override
  public V putIfAbsent(K key, V value) {
    return mutationBehaviourResolver.putIfAbsent(key, value);
  }

  @Override
  public boolean remove(Object key, Object value) {
    return mutationBehaviourResolver.remove(key, value);
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    return mutationBehaviourResolver.replace(key, oldValue, newValue);
  }

  @Override
  public V replace(K key, V value) {
    return mutationBehaviourResolver.replace(key, value);
  }

  @Override
  public int size() {
    return immutationBehaviourResolver.localSize();
  }

  @Override
  public boolean isEmpty() {
    return immutationBehaviourResolver.localSize() == 0;
  }

  @Override
  public boolean containsKey(Object key) {
    return containsLocalKey(key);
  }

  @Override
  public V get(Object key) {
    return getQuiet(key);
  }

  @Override
  public V put(K key, V value) {
    return mutationBehaviourResolver.put(key, value);
  }

  @Override
  public V remove(Object key) {
    return mutationBehaviourResolver.remove(key);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    mutationBehaviourResolver.putAll(m);
  }

  @Override
  public void clear() {
    mutationBehaviourResolver.clear();
  }

  @Override
  public Set<K> keySet() {
    return immutationBehaviourResolver.localKeySet();
  }

  @Override
  public Collection<V> values() {
    Map<K, V> allValuesMap = getAllLocalKeyValuesMap();
    return allValuesMap.values();
  }

  @Override
  public Set<java.util.Map.Entry<K, V>> entrySet() {
    Map<K, V> allValuesMap = getAllLocalKeyValuesMap();
    return allValuesMap.entrySet();
  }

  private Map<K, V> getAllLocalKeyValuesMap() {
    Map<K, V> allValuesMap = new HashMap<K, V>(immutationBehaviourResolver.localSize());
    for (K key : immutationBehaviourResolver.keySet()) {
      allValuesMap.put(key, getQuiet(key));
    }
    return allValuesMap;
  }

  @Override
  public Map<Object, Set<ClusterNode>> getNodesWithKeys(Set portableKeys) {
    return mutationBehaviourResolver.getNodesWithKeys(portableKeys);
  }

  @Override
  public void unlockedPutNoReturn(K k, V v, int createTime, int customTTI, int customTTL) {
    mutationBehaviourResolver.unlockedPutNoReturn(k, v, createTime, customTTI, customTTL);

  }

  @Override
  public void unlockedRemoveNoReturn(Object k) {
    mutationBehaviourResolver.unlockedRemoveNoReturn(k);

  }

  @Override
  public V unlockedGet(Object k, boolean quiet) {
    return getQuiet(k);
  }

  @Override
  public void clearLocalCache() {
    // TODO: discuss
    mutationBehaviourResolver.clearLocalCache();
  }

  @Override
  public V unsafeLocalGet(Object key) {
    return immutationBehaviourResolver.unsafeLocalGet(key);
  }

  @Override
  public boolean containsLocalKey(Object key) {
    return immutationBehaviourResolver.containsLocalKey(key);
  }

  @Override
  public int localSize() {
    return immutationBehaviourResolver.localSize();
  }

  @Override
  public Set<K> localKeySet() {
    return immutationBehaviourResolver.localKeySet();
  }

  @Override
  public long localOnHeapSizeInBytes() {
    return immutationBehaviourResolver.localOnHeapSizeInBytes();
  }

  @Override
  public long localOffHeapSizeInBytes() {
    return immutationBehaviourResolver.localOffHeapSizeInBytes();
  }

  @Override
  public int localOnHeapSize() {
    return immutationBehaviourResolver.localOnHeapSize();
  }

  @Override
  public int localOffHeapSize() {
    return immutationBehaviourResolver.localOffHeapSize();
  }

  @Override
  public boolean containsKeyLocalOnHeap(Object key) {
    return immutationBehaviourResolver.containsKeyLocalOnHeap(key);
  }

  @Override
  public boolean containsKeyLocalOffHeap(Object key) {
    return immutationBehaviourResolver.containsKeyLocalOffHeap(key);
  }

  @Override
  public V put(K key, V value, int createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds) {
    return mutationBehaviourResolver.put(key, value, createTimeInSecs, customMaxTTISeconds, customMaxTTLSeconds);
  }

  @Override
  public void disposeLocally() {
    // TODO: discuss
    mutationBehaviourResolver.disposeLocally();
  }

  @Override
  public void removeAll(Set<K> keys) {
    mutationBehaviourResolver.removeAll(keys);
  }

  @Override
  public QueryBuilder createQueryBuilder() {
    return immutationBehaviourResolver.createQueryBuilder();
  }

  @Override
  public void doDestroy() {
    ((DestroyableToolkitObject) mutationBehaviourResolver).doDestroy();
  }

  @Override
  public V get(K key, ObjectID valueOid) {
    // TODO: discuss change in behavior for search here.
    return immutationBehaviourResolver.unsafeLocalGet(key);
  }

  @Override
  public Map<K, V> unlockedGetAll(Collection<K> keys, boolean quiet) {
    return immutationBehaviourResolver.unlockedGetAll(keys, quiet);
  }
}
