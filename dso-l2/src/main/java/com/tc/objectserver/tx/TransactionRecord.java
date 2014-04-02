/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.tx;

import com.tc.net.NodeID;
import com.tc.util.Util;

import java.util.HashSet;
import java.util.Set;

public class TransactionRecord {
  private final TransactionState state;
  private final Set<NodeID>      waitees;

  public TransactionRecord() {
    this(null);
  }

  public TransactionRecord(final NodeID node) {
    this.waitees = new HashSet<NodeID>();
    if (node != null) {
      this.state = TransactionState.COMPLETED_STATE;
      this.waitees.add(node);
    } else {
      this.state = new TransactionState();
    }
  }

  public synchronized boolean relayTransactionComplete() {
    this.state.relayTransactionComplete();
    notifyAll();
    return this.waitees.isEmpty() && this.state.isComplete();
  }

  public synchronized boolean applyAndCommitSkipped() {
    this.state.applyAndCommitSkipped();
    notifyAll();
    return this.waitees.isEmpty() && this.state.isComplete();
  }

  public synchronized boolean applyCommitted() {
    this.state.applyCommitted();
    notifyAll();
    return this.waitees.isEmpty() && this.state.isComplete();
  }

  public synchronized boolean broadcastCompleted() {
    this.state.broadcastCompleted();
    notifyAll();
    return this.waitees.isEmpty() && this.state.isComplete();
  }
  
  public synchronized boolean processMetaDataCompleted() {
    this.state.processMetaDataCompleted();
    notifyAll();
    return this.waitees.isEmpty() && this.state.isComplete();
  }

  public synchronized boolean isComplete() {
    return this.state.isComplete() && this.waitees.isEmpty();
  }

  @Override
  public String toString() {
    return "TransactionRecord@" + System.identityHashCode(this) + " = " + this.state + "  :: waitees = " + this.waitees;
  }

  public synchronized boolean addWaitee(final NodeID waitee) {
/* this the transaction is already complete, can't add waitee and switch state back to not completed  */
    if ( this.waitees.isEmpty() && this.state.isComplete() ) {
      return false;
    }
    return this.waitees.add(waitee);
  }

  public synchronized boolean remove(final NodeID waitee) {
    if (this.waitees.remove(waitee)) {
      notifyAll();
      if ( this.waitees.isEmpty() && this.state.isComplete() ) {
        return true;
      }
    }
    return false;
  }

  public synchronized boolean isEmpty() {
    return this.waitees.isEmpty();
  }

  public synchronized boolean contains(final NodeID waitee) {
    return this.waitees.contains(waitee);
  }

  private synchronized boolean isRelayComplete() {
    if (this.state.isRelayComplete()) {
      for (NodeID waitee : waitees) {
        if (waitee.getNodeType() == NodeID.SERVER_NODE_TYPE) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  public synchronized void waitUntilRelayComplete() {
    boolean interrupted = false;
    while(!isRelayComplete()) {
      try {
        wait();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    Util.selfInterruptIfNeeded(interrupted);
  }

  public synchronized void waitUntilCommit() {
    boolean interrupted = false;
    while (!this.state.isApplyCommitted()) {
      try {
        wait();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    Util.selfInterruptIfNeeded(interrupted);
  }
}
