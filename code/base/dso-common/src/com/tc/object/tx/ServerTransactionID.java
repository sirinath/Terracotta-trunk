/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.tx;

import com.tc.bytes.TCByteBuffer;
import com.tc.bytes.TCByteBufferFactory;
import com.tc.io.TCByteBufferInputStream;
import com.tc.io.TCByteBufferOutputStream;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.net.groups.NodeIDSerializer;
import com.tc.util.Assert;

/**
 * A class that represents a particular client transaction from the server's perspective (ie. the combination of NodeID
 * and a client TransactionID)
 */
public class ServerTransactionID implements Comparable {
  public static final ServerTransactionID NULL_ID             = new ServerTransactionID(ClientID.NULL_ID,
                                                                                        TransactionID.NULL_ID);

  public final static ServerTransactionID META_DATA_IGNORE_ID = new MetaDataIgnoreTransactionID();

  private final TransactionID             txnID;
  private final NodeID                    sourceID;
  private final int                       hashCode;

  public ServerTransactionID(NodeID source, TransactionID txnID) {
    this.sourceID = source;
    this.txnID = txnID;

    int hash = 29;
    hash = (37 * hash) + source.hashCode();
    hash = (37 * hash) + txnID.hashCode();
    this.hashCode = hash;
  }

  public NodeID getSourceID() {
    return sourceID;
  }

  public TransactionID getClientTransactionID() {
    return txnID;
  }

  public boolean isServerGeneratedTransaction() {
    return (sourceID.getNodeType() == NodeID.SERVER_NODE_TYPE);
  }

  public boolean isNull() {
    return sourceID.isNull() && txnID.isNull();
  }

  @Override
  public String toString() {
    return new StringBuffer().append("ServerTransactionID{").append(sourceID).append(',').append(txnID).append('}')
        .toString();
  }

  @Override
  public int hashCode() {
    return this.hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof ServerTransactionID) {
      ServerTransactionID other = (ServerTransactionID) obj;
      return this.sourceID.equals(other.sourceID) && this.txnID.equals(other.txnID);
    }
    return false;
  }

  /**
   * Utility method for serialization.
   */
  public byte[] getBytes() {
    TCByteBufferOutputStream out = new TCByteBufferOutputStream(64, false);
    NodeIDSerializer nodeIDSerializer = new NodeIDSerializer(sourceID);
    nodeIDSerializer.serializeTo(out);
    out.writeLong(txnID.toLong());
    out.close();
    TCByteBuffer[] bufs = out.toArray();
    Assert.assertEquals(1, bufs.length);
    return bufs[0].array();
  }

  /**
   * Utility method for deserialization.
   */
  public static ServerTransactionID createFrom(byte[] data) {
    try {
      TCByteBufferInputStream in = new TCByteBufferInputStream(TCByteBufferFactory.wrap(data));
      NodeIDSerializer nodeIDSerializer = new NodeIDSerializer();
      nodeIDSerializer.deserializeFrom(in);
      return new ServerTransactionID(nodeIDSerializer.getNodeID(), new TransactionID(in.readLong()));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  public int compareTo(Object o) {
    ServerTransactionID other = (ServerTransactionID) o;
    int cmp = sourceID.compareTo(other.sourceID);
    if (cmp == 0) {
      return txnID.compareTo(other.txnID);
    } else {
      return cmp;
    }
  }

  private static class MetaDataIgnoreTransactionID extends ServerTransactionID {
    // make this a separate subclass so it can never be equal() to a "real" ServerTransactionID

    MetaDataIgnoreTransactionID() {
      super(ClientID.NULL_ID, TransactionID.NULL_ID);
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this;
    }
  }

}
