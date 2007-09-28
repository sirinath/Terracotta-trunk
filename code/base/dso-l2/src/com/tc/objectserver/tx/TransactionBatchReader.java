/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.tx;

import com.tc.net.groups.NodeID;
import com.tc.object.tx.TxnBatchID;

import java.io.IOException;
import java.util.Collection;

public interface TransactionBatchReader {
  public ServerTransaction getNextTransaction() throws IOException;

  public TxnBatchID getBatchID();

  public int getNumTxns();

  public NodeID getNodeID();

  public Collection addAcknowledgedTransactionIDsTo(Collection c);

}
