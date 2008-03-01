/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.tx;

import com.tc.object.tx.ServerTransactionID;
import com.tc.objectserver.context.CommitTransactionContext;
import com.tc.objectserver.context.RecallObjectsContext;
import com.tc.text.PrettyPrinter;


import java.io.Writer;
import java.util.Collection;

public class NullTransactionalObjectManager implements TransactionalObjectManager {

  public void addTransactions(Collection txns) {
    // Nop
  }

  public boolean applyTransactionComplete(ServerTransactionID stxnID) {
    // Nop
    return false;
  }

  public void lookupObjectsForTransactions() {
    // Nop
  }

  public void commitTransactionsComplete(CommitTransactionContext ctc) {
    // Nop
  }

  public void processApplyComplete() {
    // Nop
  }

  public void recallAllCheckedoutObject() {
    // Nop
  }

  public void recallCheckedoutObject(RecallObjectsContext roc) {
    // Nop
  }

  public String dump() {
    return null;
  }

  public void dump(Writer writer) {
    // Nop
  }

  public void dumpToLogger() {
    // Nop
  }

  public PrettyPrinter prettyPrint(PrettyPrinter out) {
    return null;
  }
}
