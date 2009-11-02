/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.gtx;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.NodeID;
import com.tc.object.locks.LockFlushCallback;
import com.tc.object.locks.LockID;
import com.tc.object.tx.RemoteTransactionManager;
import com.tc.object.tx.ServerTransactionID;
import com.tc.object.tx.TransactionID;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

public class ClientGlobalTransactionManagerImpl implements ClientGlobalTransactionManager {

  private static final TCLogger          logger               = TCLogging
                                                                  .getLogger(ClientGlobalTransactionManagerImpl.class);

  private static final int               ALLOWED_LWM_DELTA    = 100;
  private final Set                      applied              = new HashSet();
  private final SortedMap                globalTransactionIDs = new TreeMap();

  private GlobalTransactionID            lowWatermark         = GlobalTransactionID.NULL_ID;
  private final RemoteTransactionManager remoteTransactionManager;
  private int                            ignoredCount         = 0;

  public ClientGlobalTransactionManagerImpl(RemoteTransactionManager remoteTransactionManager) {
    this.remoteTransactionManager = remoteTransactionManager;
  }

  // For testing
  public synchronized int size() {
    return applied.size();
  }

  // For testing
  public int getAllowedLowWaterMarkDelta() {
    return ALLOWED_LWM_DELTA;
  }

  public synchronized boolean startApply(NodeID committerID, TransactionID transactionID, GlobalTransactionID gtxID,
                                         NodeID remoteGroupID) {
    if (gtxID.lessThan(getLowGlobalTransactionIDWatermark())) {
      // formatting
      throw new UnknownTransactionError("Attempt to apply a transaction lower than the low watermark: gtxID = " + gtxID
                                        + ", low watermark = " + getLowGlobalTransactionIDWatermark());
    }
    ServerTransactionID serverTransactionID = new ServerTransactionID(committerID, transactionID);
    globalTransactionIDs.put(gtxID, serverTransactionID);
    return applied.add(serverTransactionID);
  }

  public synchronized GlobalTransactionID getLowGlobalTransactionIDWatermark() {
    return lowWatermark;
  }

  public synchronized void setLowWatermark(GlobalTransactionID lowWatermark, NodeID nodeID) {
    if (this.lowWatermark.toLong() > lowWatermark.toLong()) {
      // XXX::This case is possible when the server crashes, Eventually the server will catch up
      logger.warn("Low water mark lower than exisiting one : mine : " + this.lowWatermark + " server sent : "
                  + lowWatermark);
      return;
    }
    if (this.lowWatermark.toLong() + ALLOWED_LWM_DELTA > lowWatermark.toLong()) {
      if (ignoredCount++ > ALLOWED_LWM_DELTA * 100) {
        logger.warn("Current Low water Mark = " + this.lowWatermark + " Server sent " + lowWatermark);
        logger.warn("Server didnt send a Low water mark higher than ALLOWED_LWM_DELTA for " + ignoredCount
                    + " times. applied.size() = " + applied.size() + " Resetting count.");
        ignoredCount = 0;
      }
      return;
    }
    this.ignoredCount = 0;
    this.lowWatermark = lowWatermark;
    Map toDelete = globalTransactionIDs.headMap(lowWatermark);
    for (Iterator i = toDelete.entrySet().iterator(); i.hasNext();) {
      Entry e = (Entry) i.next();
      ServerTransactionID stxID = (ServerTransactionID) e.getValue();
      i.remove();
      applied.remove(stxID);
    }
  }

  public void flush(LockID lockID) {
    remoteTransactionManager.flush(lockID);
  }

  public boolean isTransactionsForLockFlushed(LockID lockID, LockFlushCallback callback) {
    return remoteTransactionManager.isTransactionsForLockFlushed(lockID, callback);
  }

}
