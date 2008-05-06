/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object.msg;

import com.tc.bytes.TCByteBuffer;
import com.tc.net.groups.ClientID;
import com.tc.object.dna.impl.ObjectStringSerializer;
import com.tc.object.tx.TransactionBatch;

/**
 * @author steve
 */
public interface CommitTransactionMessage {

  public ObjectStringSerializer getSerializer();

  public void setBatch(TransactionBatch batch, ObjectStringSerializer serializer);

  public TCByteBuffer[] getBatchData();
  
  public void send();
  
  public ClientID getClientID();
  
}
