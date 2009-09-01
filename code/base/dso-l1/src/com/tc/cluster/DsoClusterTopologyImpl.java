/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.cluster;

import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.util.Assert;
import com.tcclient.cluster.DsoClusterInternal;
import com.tcclient.cluster.DsoNode;
import com.tcclient.cluster.DsoNodeImpl;
import com.tcclient.cluster.DsoNodeInternal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DsoClusterTopologyImpl implements DsoClusterTopology {
  private final DsoClusterInternal               cluster;
  private final Map<NodeID, DsoNodeInternal>     nodes          = new HashMap<NodeID, DsoNodeInternal>();

  private final ReentrantReadWriteLock           nodesLock      = new ReentrantReadWriteLock();
  private final ReentrantReadWriteLock.ReadLock  nodesReadLock  = nodesLock.readLock();
  private final ReentrantReadWriteLock.WriteLock nodesWriteLock = nodesLock.writeLock();

  DsoClusterTopologyImpl(DsoClusterInternal cluster) {
    this.cluster = cluster;
  }

  public Collection<DsoNode> getNodes() {
    nodesReadLock.lock();
    try {
      // yucky cast hack for generics
      return (Collection) Collections.unmodifiableCollection(new ArrayList<DsoNodeInternal>(nodes.values()));
    } finally {
      nodesReadLock.unlock();
    }
  }

  boolean containsDsoNode(final NodeID nodeId) {
    nodesReadLock.lock();
    try {
      return nodes.containsKey(nodeId);
    } finally {
      nodesReadLock.unlock();
    }
  }

  DsoNodeInternal getAndRegisterDsoNode(final NodeID nodeId) {
    nodesReadLock.lock();
    try {
      DsoNodeInternal node = nodes.get(nodeId);
      if (node != null) { return node; }
    } finally {
      nodesReadLock.unlock();
    }

    return registerDsoNode(nodeId);
  }

  DsoNodeInternal getAndRemoveDsoNode(final NodeID nodeId) {
    nodesWriteLock.lock();
    try {
      DsoNodeInternal node = nodes.remove(nodeId);
      Assert.assertNotNull(node);
      return node;
    } finally {
      nodesWriteLock.unlock();
    }
  }

  DsoNodeInternal registerDsoNode(final NodeID nodeId) {
    final ClientID clientId = (ClientID) nodeId;
    final DsoNodeInternal node = new DsoNodeImpl(clientId.toString(), clientId.toLong());

    nodesWriteLock.lock();
    try {
      nodes.put(nodeId, node);
    } finally {
      nodesWriteLock.unlock();
    }

    node.getOrRetrieveMetaData(cluster);

    return node;
  }
}