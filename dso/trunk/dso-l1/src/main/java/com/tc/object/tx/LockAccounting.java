/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.tx;

import com.tc.abortable.AbortableOperationManager;
import com.tc.abortable.AbortedOperationException;
import com.tc.exception.PlatformRejoinException;
import com.tc.exception.TCNotRunningException;
import com.tc.object.ClearableCallback;
import com.tc.object.locks.LockID;
import com.tc.util.AbortedOperationUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LockAccounting implements ClearableCallback {
  private static final long                              WAIT_FOR_TRANSACTIONS_INTERVAL = TimeUnit.SECONDS
                                                                                            .toMillis(10L);

  private final CopyOnWriteArrayList<TxnRemovedListener> listeners                      = new CopyOnWriteArrayList<TxnRemovedListener>();

  private final Map<TransactionIDWrapper, Set<LockID>>   tx2Locks                       = new HashMap();
  private final Map<LockID, Set<TransactionIDWrapper>>   lock2Txs                       = new HashMap();
  private final Map<TransactionID, TransactionIDWrapper> tid2wrap                       = new HashMap();
  private volatile boolean                               shutdown                       = false;
  private final AbortableOperationManager                abortableOperationManager;
  private final RemoteTransactionManagerImpl             remoteTxnMgrImpl;

  public LockAccounting(AbortableOperationManager abortableOperationManager,
                        RemoteTransactionManagerImpl remoteTxnMgrImpl) {
    this.abortableOperationManager = abortableOperationManager;
    this.remoteTxnMgrImpl = remoteTxnMgrImpl;
  }

  @Override
  public synchronized void cleanup() {
    for (TxnRemovedListener listener : listeners) {
      listener.release();
    }
    listeners.clear();
    tx2Locks.clear();
    lock2Txs.clear();
    tid2wrap.clear();
  }

  public synchronized Object dump() {
    return toString();
  }

  public void shutdown() {
    this.shutdown = true;
  }

  @Override
  public synchronized String toString() {
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
      if (txnsRecvd.contains(txIDWrapper.getTransactionID())) {
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
        if (!txIDs.remove(txIDWrapper)) {
            throw new AssertionError("No lock=>transaction found for " + lid + ", " + txID);
        }
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
    tid2wrap.remove(txnIDWrapper.getTransactionID());
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

  public void waitAllCurrentTxnCompleted() throws AbortedOperationException {
    TxnRemovedListener listener;
    CountDownLatch latch = null;
    synchronized (this) {
      latch = new CountDownLatch(tx2Locks.size());
      listener = new TxnRemovedListener(new HashSet(tx2Locks.keySet()), latch);
      listeners.add(listener);
    }

    boolean txnsCompleted = false;
    boolean interrupted = false;
    try {
      // DEV-6271: During rejoin, the client could be shut down. In that case we need to get
      // out of this wait and throw a TCNotRunningException for upper layers to handle
      do {
        try {
          if (shutdown) { throw new TCNotRunningException(); }
          if (remoteTxnMgrImpl.isRejoinInProgress()) { throw new PlatformRejoinException(); }
          txnsCompleted = latch.await(WAIT_FOR_TRANSACTIONS_INTERVAL, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          AbortedOperationUtil.throwExceptionIfAborted(abortableOperationManager);
          interrupted = true;
        }
      } while (!txnsCompleted);
    } finally {
      synchronized (this) {
        listeners.remove(listener);
      }

      if (interrupted) {
        Thread.currentThread().interrupt();
      }
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

  private class TxnRemovedListener {
    private final Set<TransactionIDWrapper>         txnSet;
    private final CountDownLatch                     latch;
    // private boolean released = false;

    TxnRemovedListener(Set<TransactionIDWrapper> txnSet, CountDownLatch latch) {
      if ( !Thread.holdsLock(LockAccounting.this) ) {
          throw new AssertionError();
      }
      this.txnSet = txnSet;
      this.latch = latch;
    }

    void txnRemoved(TransactionIDWrapper txnID) {
//  locked several frames up
      if ( !Thread.holdsLock(LockAccounting.this) ) {
          throw new AssertionError();
      }
      if ( this.txnSet.remove(txnID) ) {
        this.latch.countDown();
      } else {
//  not interested in this transaction
      }
    }
    
    void release() {
//  locked several frames up
      if ( !Thread.holdsLock(LockAccounting.this) ) {
          throw new AssertionError();
      }
      txnSet.clear();
        while ( this.latch.getCount() > 0 ) {
            latch.countDown();
        }
      // released = true;
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
      if (obj == null || getClass() != obj.getClass()) return false;
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

  // for testing purpose only
  int sizeOfTransactionMap() {
    return tx2Locks.size();
  }

  // for testing purpose only
  int sizeOfLockMap() {
    return lock2Txs.size();
  }

  // for testing purpose only
  int sizeOfIDWrapMap() {
    return tid2wrap.size();
  }

}