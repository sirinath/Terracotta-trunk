/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.objectserver;

import com.tc.net.NodeID;
import com.tc.object.dmi.DmiDescriptor;
import com.tc.object.dna.impl.ObjectStringSerializer;
import com.tc.object.gtx.GlobalTransactionID;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.tx.ServerTransactionID;
import com.tc.object.tx.TransactionID;
import com.tc.object.tx.TxnBatchID;
import com.tc.object.tx.TxnType;
import com.tc.objectserver.tx.ServerTransaction;
import com.tc.util.Assert;
import com.tc.util.SequenceID;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ObjectSyncServerTransaction implements ServerTransaction {

  private final TransactionID       txnID;
  private final Set                 oids;
  private final List                changes;
  private final ServerTransactionID serverTxnID;
  private final Map                 rootsMap;
  private final NodeID              serverID;

  public ObjectSyncServerTransaction(ServerTransactionID serverTransactionID, Set oids, int dnaCount, List changes,
                                     Map rootsMap, NodeID serverID) {
    this.oids = oids;
    this.changes = changes;
    this.rootsMap = rootsMap;
    this.serverID = serverID;
    this.serverTxnID = serverTransactionID;
    this.txnID = serverTransactionID.getClientTransactionID();
    Assert.assertEquals(serverID, serverTransactionID.getSourceID());
    Assert.assertEquals(dnaCount, oids.size());
    Assert.assertEquals(dnaCount, changes.size());
  }

  public Collection getNotifies() {
    return Collections.EMPTY_LIST;
  }

  public TxnBatchID getBatchID() {
    return TxnBatchID.NULL_BATCH_ID;
  }

  public List getChanges() {
    return changes;
  }

  public NodeID getSourceID() {
    return serverID;
  }

  public SequenceID getClientSequenceID() {
    return SequenceID.NULL_ID;
  }

  public DmiDescriptor[] getDmiDescriptors() {
    throw new UnsupportedOperationException();
  }

  public LockID[] getLockIDs() {
    return new LockID[0];
  }

  public Map getNewRoots() {
    return rootsMap;
  }

  public Set getObjectIDs() {
    return oids;
  }

  /*
   * All objects contained in ObjectSync Message are new Objects for the passive
   */
  public Set getNewObjectIDs() {
    return oids;
  }

  public ObjectStringSerializer getSerializer() {
    throw new UnsupportedOperationException();
  }

  public ServerTransactionID getServerTransactionID() {
    return serverTxnID;
  }

  public TransactionID getTransactionID() {
    return txnID;
  }

  public TxnType getTransactionType() {
    return TxnType.NORMAL;
  }

  // XXX:: this is server generated txn, hence GID is not assigned.
  public GlobalTransactionID getGlobalTransactionID() {
    return GlobalTransactionID.NULL_ID;
  }

  public boolean needsBroadcast() {
    return false;
  }

  public int getNumApplicationTxn() {
    return 1;
  }

}
