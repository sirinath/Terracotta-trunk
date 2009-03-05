/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.cluster;

import com.tc.cluster.exceptions.ClusteredListenerException;
import com.tc.cluster.exceptions.UnclusteredObjectException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.net.protocol.tcm.ChannelID;
import com.tc.object.ClientObjectManager;
import com.tc.object.ClusterMetaDataManager;
import com.tc.object.ObjectID;
import com.tc.object.bytecode.Manageable;
import com.tc.object.bytecode.TCMap;
import com.tc.util.Assert;
import com.tcclient.cluster.DsoClusterInternal;
import com.tcclient.cluster.DsoNode;
import com.tcclient.cluster.DsoNodeInternal;
import com.tcclient.cluster.DsoNodeMetaData;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DsoClusterImpl implements DsoClusterInternal {

  private static final TCLogger                  LOGGER               = TCLogging.getLogger(DsoClusterImpl.class);

  private volatile DsoNodeInternal               currentNode;

  private final DsoClusterTopologyImpl           topology             = new DsoClusterTopologyImpl();
  private final List<DsoClusterListener>         listeners            = new CopyOnWriteArrayList<DsoClusterListener>();

  private final ReentrantReadWriteLock           stateLock            = new ReentrantReadWriteLock();
  private final ReentrantReadWriteLock.ReadLock  stateReadLock        = stateLock.readLock();
  private final ReentrantReadWriteLock.WriteLock stateWriteLock       = stateLock.writeLock();

  private ClusterMetaDataManager                 clusterMetaDataManager;
  private ClientObjectManager                    clientObjectManager;

  private boolean                                isNodeJoined         = false;
  private boolean                                areOperationsEnabled = false;

  public void init(final ClusterMetaDataManager metaDataManager, final ClientObjectManager objectManager) {
    this.clusterMetaDataManager = metaDataManager;
    this.clientObjectManager = objectManager;
  }

  public void addClusterListener(final DsoClusterListener listener) throws ClusteredListenerException {
    if (null == listeners) { return; }

    stateWriteLock.lock();
    try {
      if (listeners.contains(listener)) { return; }
      listeners.add(listener);
    } finally {
      stateWriteLock.unlock();
    }

    if (isNodeJoined) {
      fireNodeJoinedInternal(new DsoClusterEventImpl(currentNode), listener);
    }

    if (areOperationsEnabled) {
      fireOperationsEnabledInternal(new DsoClusterEventImpl(currentNode), listener);
    }
  }

  public void removeClusterListener(final DsoClusterListener listener) {
    stateWriteLock.lock();
    try {
      listeners.remove(listener);
    } finally {
      stateWriteLock.unlock();
    }
  }

  public DsoNode getCurrentNode() {
    stateReadLock.lock();
    try {
      return currentNode;
    } finally {
      stateReadLock.unlock();
    }
  }

  public DsoClusterTopology getClusterTopology() {
    return topology;
  }

  public Set<DsoNode> getNodesWithObject(final Object object) throws UnclusteredObjectException {
    Assert.assertNotNull(clusterMetaDataManager);

    if (null == object) { return Collections.emptySet(); }

    // we might have to use ManagerUtil.lookupExistingOrNull(object) here if we need to support literals
    if (object instanceof Manageable) {
      Manageable manageable = (Manageable) object;
      if (manageable.__tc_isManaged()) {
        Set<NodeID> response = clusterMetaDataManager.getNodesWithObject(manageable.__tc_managed().getObjectID());
        if (response.isEmpty()) { return Collections.emptySet(); }

        final Set<DsoNode> result = new HashSet<DsoNode>();
        for (NodeID nodeID : response) {
          result.add(topology.getDsoNode(nodeID));
        }

        return result;
      }
    }

    throw new UnclusteredObjectException(object);
  }

  public Map<?, Set<DsoNode>> getNodesWithObjects(final Object... objects) throws UnclusteredObjectException {
    Assert.assertNotNull(clusterMetaDataManager);

    if (null == objects || 0 == objects.length) { return Collections.emptyMap(); }

    return getNodesWithObjects(Arrays.asList(objects));
  }

  public Map<?, Set<DsoNode>> getNodesWithObjects(final Collection<?> objects) throws UnclusteredObjectException {
    Assert.assertNotNull(clusterMetaDataManager);

    if (null == objects || 0 == objects.size()) { return Collections.emptyMap(); }

    // ensure that all objects in the collection are managed and collect their object IDs
    // we might have to use ManagerUtil.lookupExistingOrNull(object) here if we need to support literals
    final HashMap<ObjectID, Object> objectIDMapping = new HashMap<ObjectID, Object>();
    for (Object object : objects) {
      if (object != null) {
        if (!(object instanceof Manageable)) {
          throw new UnclusteredObjectException(object);
        } else {
          Manageable manageable = (Manageable) object;
          if (!manageable.__tc_isManaged()) {
            throw new UnclusteredObjectException(object);
          } else {
            objectIDMapping.put(manageable.__tc_managed().getObjectID(), object);
          }
        }
      }
    }

    if (0 == objectIDMapping.size()) { return Collections.emptyMap(); }

    // retrieve the object locality information from the L2
    final Map<ObjectID, Set<NodeID>> response = clusterMetaDataManager.getNodesWithObjects(objectIDMapping.keySet());
    if (response.isEmpty()) { return Collections.emptyMap(); }

    // transform object IDs and node IDs in actual local instances
    Map<Object, Set<DsoNode>> result = new HashMap<Object, Set<DsoNode>>();
    for (Map.Entry<ObjectID, Set<NodeID>> entry : response.entrySet()) {
      final Object object = objectIDMapping.get(entry.getKey());
      Assert.assertNotNull(object);

      final Set<DsoNode> dsoNodes = new HashSet<DsoNode>();
      for (NodeID nodeID : entry.getValue()) {
        dsoNodes.add(topology.getDsoNode(nodeID));
      }
      result.put(object, dsoNodes);
    }

    return result;
  }

  public <K> Set<K> getKeysForOrphanedValues(final Map<K, ?> map) throws UnclusteredObjectException {
    Assert.assertNotNull(clusterMetaDataManager);

    if (null == map) { return Collections.emptySet(); }

    if (map instanceof Manageable) {
      Manageable manageable = (Manageable) map;
      if (manageable.__tc_isManaged()) {
        if (manageable instanceof TCMap) {
          final Set<K> result = new HashSet<K>();
          final Set keys = clusterMetaDataManager.getKeysForOrphanedValues(manageable.__tc_managed().getObjectID());
          for (Object key : keys) {
            if (key instanceof ObjectID) {
              try {
                result.add((K) clientObjectManager.lookupObject((ObjectID) key));
              } catch (ClassNotFoundException e) {
                Assert.fail("Unexpected ClassNotFoundException for key '" + key + "' : " + e.getMessage());
              }
            } else {
              result.add((K) key);
            }
          }
          return result;
        } else {
          return Collections.emptySet();
        }
      }
    }

    throw new UnclusteredObjectException(map);
  }

  public <K> Set<K> getKeysForLocalValues(final Map<K, ?> map) throws UnclusteredObjectException {
    if (null == map) { return Collections.emptySet(); }

    if (map instanceof Manageable) {
      Manageable manageable = (Manageable) map;
      if (manageable.__tc_isManaged()) {
        if (manageable instanceof TCMap) {
          final Collection<Map.Entry> localEntries = ((TCMap) manageable).__tc_getAllLocalEntriesSnapshot();
          if (0 == localEntries.size()) { return Collections.emptySet(); }

          final Set<K> result = new HashSet<K>();
          for (Map.Entry entry : localEntries) {
            result.add((K) entry.getKey());
          }

          return result;
        } else {
          return Collections.emptySet();
        }
      }
    }

    throw new UnclusteredObjectException(map);
  }

  public DsoNodeMetaData retrieveMetaDataForDsoNode(final DsoNodeInternal node) {
    Assert.assertNotNull(clusterMetaDataManager);

    return clusterMetaDataManager.retrieveMetaDataForDsoNode(node);
  }

  public boolean isNodeJoined() {
    stateReadLock.lock();
    try {
      return isNodeJoined;
    } finally {
      stateReadLock.unlock();
    }
  }

  public boolean areOperationsEnabled() {
    stateReadLock.lock();
    try {
      return areOperationsEnabled;
    } finally {
      stateReadLock.unlock();
    }
  }

  public void fireThisNodeJoined(final NodeID nodeId, final NodeID[] clusterMembers) {
    stateWriteLock.lock();
    try {
      // we might get multiple calls in a row, ignore all but the first in a row.
      if (currentNode != null) return;

      currentNode = topology.registerDsoNode(nodeId);
      isNodeJoined = true;

      for (NodeID otherNodeId : clusterMembers) {
        topology.registerDsoNode(otherNodeId);
      }
    } finally {
      stateWriteLock.unlock();
    }

    fireNodeJoined(nodeId);
  }

  public void fireThisNodeLeft() {
    boolean fireOperationsDisabled = false;
    stateWriteLock.lock();
    try {
      if (areOperationsEnabled) {
        fireOperationsDisabled = true;
        areOperationsEnabled = false;
      }
    } finally {
      stateWriteLock.unlock();
    }

    if (fireOperationsDisabled) {
      fireOperationsDisabledInternal();
    }

    stateWriteLock.lock();
    try {
      if (!isNodeJoined) { return; }
      isNodeJoined = false;
    } finally {
      stateWriteLock.unlock();
    }

    fireNodeLeft(new ClientID(new ChannelID(currentNode.getChannelId())));
  }

  public void fireNodeJoined(final NodeID nodeId) {
    final DsoClusterEvent event = new DsoClusterEventImpl(topology.getDsoNode(nodeId));
    for (DsoClusterListener listener : listeners) {
      fireNodeJoinedInternal(event, listener);
    }
  }

  private void fireNodeJoinedInternal(final DsoClusterEvent event, final DsoClusterListener listener) {
    try {
      listener.nodeJoined(event);
    } catch (Throwable e) {
      log(e);
    }
  }

  public void fireNodeLeft(final NodeID nodeId) {
    final DsoClusterEvent event = new DsoClusterEventImpl(topology.getAndRemoveDsoNode(nodeId));
    for (DsoClusterListener listener : listeners) {
      try {
        listener.nodeLeft(event);
      } catch (Throwable e) {
        log(e);
      }
    }
  }

  public void fireOperationsEnabled() {
    if (currentNode != null) {
      stateWriteLock.lock();
      try {
        if (areOperationsEnabled) { return; }
        areOperationsEnabled = true;
      } finally {
        stateWriteLock.unlock();
      }

      final DsoClusterEvent event = new DsoClusterEventImpl(currentNode);
      for (DsoClusterListener listener : listeners) {
        fireOperationsEnabledInternal(event, listener);
      }
    }
  }

  private void fireOperationsEnabledInternal(final DsoClusterEvent event, final DsoClusterListener listener) {
    try {
      listener.operationsEnabled(event);
    } catch (Throwable e) {
      log(e);
    }
  }

  public void fireOperationsDisabled() {
    stateWriteLock.lock();
    try {
      if (!areOperationsEnabled) { return; }

      areOperationsEnabled = false;
    } finally {
      stateWriteLock.unlock();
    }

    fireOperationsDisabledInternal();
  }

  private void fireOperationsDisabledInternal() {
    final DsoClusterEvent event = new DsoClusterEventImpl(currentNode);
    for (DsoClusterListener listener : listeners) {
      try {
        listener.operationsDisabled(event);
      } catch (Throwable e) {
        log(e);
      }
    }
  }

  private void log(final Throwable t) {
    LOGGER.error("Unhandled exception in event callback " + this, t);
  }
}