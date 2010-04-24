/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.tx;

import EDU.oswego.cs.dl.util.concurrent.Latch;

import com.tc.exception.TCRuntimeException;
import com.tc.object.locks.LockID;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class LockAccounting {
  private final CopyOnWriteArrayList<TxnRemovedListener> listeners = new CopyOnWriteArrayList<TxnRemovedListener>();

  private final Map<TransactionIDWrapper, Set<LockID>>   tx2Locks  = new HashMap();
  private final Map<LockID, Set<TransactionIDWrapper>>   lock2Txs  = new HashMap();
  private final Map<TransactionID, TransactionIDWrapper> tid2wrap  = new HashMap();

  public synchronized Object dump() {
    return toString();
  }

  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("Lock Accounting:\n");
    builder.append("[tx2Locks=" + tx2Locks + ", lock2Txs=" + lock2Txs + "]");
    return builder.toString();
  }

  public synchronized void add(TransactionID txID, Collection lockIDs) {
    TransactionIDWrapper txIDWrapper = getOrCreateWrapperFor(txID);
    getOrCreateSetFor(txIDWrapper, tx2Locks).addAll(lockIDs);
    for (Iterator i = lockIDs.iterator(); i.hasNext();) {
      LockID lid = (LockID) i.next();
      getOrCreateSetFor(lid, lock2Txs).add(txIDWrapper);
    }
  }

  public synchronized Collection getTransactionsFor(LockID lockID) {
    Set rv = new HashSet();
    Set<TransactionIDWrapper> toAdd = lock2Txs.get(lockID);

    if (toAdd != null) {
      for (TransactionIDWrapper txIDWrapper : toAdd) {
        rv.add(txIDWrapper.getTransactionID());
      }
    }
    return rv;
  }

  public synchronized boolean areTransactionsReceivedForThisLockID(LockID lockID) {
    Set<TransactionIDWrapper> txnsForLockID = lock2Txs.get(lockID);

    if (txnsForLockID == null || txnsForLockID.isEmpty()) { return true; }

    for (TransactionIDWrapper txIDWrapper : txnsForLockID) {
      if (!txIDWrapper.isReceived()) { return false; }
    }

    return true;
  }

  public synchronized void transactionRecvdByServer(Set<TransactionID> txnsRecvd) {
    Set<TransactionIDWrapper> txnSet = tx2Locks.keySet();

    if (txnSet == null) { return; }

    for (TransactionIDWrapper txIDWrapper : txnSet) {
      if (txnsRecvd.contains(txIDWrapper)) {
        txIDWrapper.received();
      }
    }
  }

  // This method returns a set of lockIds that has no more transactions to wait for
  public synchronized Set acknowledge(TransactionID txID) {
    Set completedLockIDs = null;
    TransactionIDWrapper txIDWrapper = new TransactionIDWrapper(txID);
    Set lockIDs = getSetFor(txIDWrapper, tx2Locks);
    if (lockIDs != null) {
      // this may be null if there are phantom acknowledgments caused by server restart.
      for (Iterator i = lockIDs.iterator(); i.hasNext();) {
        LockID lid = (LockID) i.next();
        Set txIDs = getOrCreateSetFor(lid, lock2Txs);
        if (!txIDs.remove(txIDWrapper)) throw new AssertionError("No lock=>transaction found for " + lid + ", " + txID);
        if (txIDs.isEmpty()) {
          lock2Txs.remove(lid);
          if (completedLockIDs == null) {
            completedLockIDs = new HashSet();
          }
          completedLockIDs.add(lid);
        }
      }
    }
    removeTxn(txIDWrapper);
    return (completedLockIDs == null ? Collections.EMPTY_SET : completedLockIDs);
  }

  private void removeTxn(TransactionIDWrapper txnIDWrapper) {
    tx2Locks.remove(txnIDWrapper);
    notifyTxnRemoved(txnIDWrapper);
  }

  private void notifyTxnRemoved(TransactionIDWrapper txnID) {
    for (TxnRemovedListener l : listeners) {
      l.txnRemoved(txnID);
    }
  }

  public synchronized boolean isEmpty() {
    return tx2Locks.isEmpty() && lock2Txs.isEmpty();
  }

  public void waitAllCurrentTxnCompleted() {
    TxnRemovedListener listener;
    Latch latch = new Latch();
    synchronized (this) {
      Set currentTxnSet = new HashSet(tx2Locks.keySet());
      listener = new TxnRemovedListener(currentTxnSet, latch);
      listeners.add(listener);
    }
    try {
      latch.acquire();
    } catch (InterruptedException e) {
      throw new TCRuntimeException(e);
    } finally {
      listeners.remove(listener);
    }
  }

  private static Set getSetFor(Object key, Map m) {
    return (Set) m.get(key);
  }

  private static Set getOrCreateSetFor(Object key, Map m) {
    Set rv = getSetFor(key, m);
    if (rv == null) {
      rv = new HashSet();
      m.put(key, rv);
    }
    return rv;
  }

  private TransactionIDWrapper getOrCreateWrapperFor(TransactionID txID) {
    TransactionIDWrapper rv = tid2wrap.get(txID);
    if (rv == null) {
      rv = new TransactionIDWrapper(txID);
      tid2wrap.put(txID, rv);
    }
    return rv;
  }

  private static class TxnRemovedListener {
    private final Set<TransactionIDWrapper> txnSet;
    private final Latch                     latch;

    TxnRemovedListener(Set<TransactionIDWrapper> txnSet, Latch latch) {
      this.txnSet = txnSet;
      this.latch = latch;
      if (txnSet.size() == 0) allTxnCompleted();
    }

    void txnRemoved(TransactionIDWrapper txnID) {
      this.txnSet.remove(txnID);
      if (txnSet.size() == 0) allTxnCompleted();
    }

    void allTxnCompleted() {
      this.latch.release();
    }

  }

  private static class TransactionIDWrapper {
    private final TransactionID txID;
    private boolean             isReceived = false;

    public TransactionIDWrapper(TransactionID txID) {
      this.txID = txID;
    }

    public TransactionID getTransactionID() {
      return this.txID;
    }

    public void received() {
      isReceived = true;
    }

    public boolean isReceived() {
      return isReceived;
    }

    @Override
    public int hashCode() {
      return txID.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (getClass() != obj.getClass()) return false;
      TransactionIDWrapper other = (TransactionIDWrapper) obj;
      if (txID == null) {
        if (other.txID != null) return false;
      } else if (!txID.equals(other.txID)) return false;
      return true;
    }

    @Override
    public String toString() {
      return "TransactionIDWrapper [isReceived=" + isReceived + ", txID=" + txID + "]";
    }
  }

}
