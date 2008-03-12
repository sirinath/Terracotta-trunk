/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.tx;

import com.tc.net.groups.ClientID;
import com.tc.net.protocol.tcm.ChannelID;
import com.tc.object.MockTCObject;
import com.tc.object.ObjectID;
import com.tc.object.bytecode.MockClassProvider;
import com.tc.object.dna.api.DNAEncoding;
import com.tc.object.dna.impl.DNAEncodingImpl;
import com.tc.object.dna.impl.ObjectStringSerializer;
import com.tc.object.gtx.DefaultGlobalTransactionIDGenerator;
import com.tc.object.gtx.GlobalTransactionIDGenerator;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.Notify;
import com.tc.object.lockmanager.api.ThreadID;
import com.tc.object.logging.NullRuntimeLogger;
import com.tc.object.tx.ClientTransaction;
import com.tc.object.tx.ClientTransactionImpl;
import com.tc.object.tx.TestClientTransaction;
import com.tc.object.tx.TransactionBatchWriter;
import com.tc.object.tx.TransactionContext;
import com.tc.object.tx.TransactionContextImpl;
import com.tc.object.tx.TransactionID;
import com.tc.object.tx.TxnBatchID;
import com.tc.object.tx.TxnType;
import com.tc.util.SequenceID;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import junit.framework.TestCase;

public class TransactionBatchTest extends TestCase {

  private DNAEncoding                         encoding = new DNAEncodingImpl(new MockClassProvider());

  private TransactionBatchWriter              writer;
  private TestCommitTransactionMessageFactory messageFactory;

  private GlobalTransactionIDGenerator        gidGenerator;

  public void setUp() throws Exception {
    ObjectStringSerializer serializer = new ObjectStringSerializer();
    messageFactory = new TestCommitTransactionMessageFactory();
    writer = new TransactionBatchWriter(new TxnBatchID(1), serializer, encoding, messageFactory);
    gidGenerator = new DefaultGlobalTransactionIDGenerator();
  }

  public void testGetMinTransaction() throws Exception {
    LinkedList list = new LinkedList();
    for (int i = 0; i < 100; i++) {
      TestClientTransaction tx = new TestClientTransaction();
      tx.sequenceID = new SequenceID(i);
      tx.txID = new TransactionID(i);
      tx.txnType = TxnType.NORMAL;
      list.add(tx);
      writer.addTransaction(tx);
    }

    writer.wait4AllTxns2Serialize();

    assertSame(((ClientTransaction) list.getFirst()).getSequenceID(), writer.getMinTransactionSequence());

    // remove some from the middle and make sure the min is constant
    for (int i = 50; i < 55; i++) {
      ClientTransaction tx = (ClientTransaction) list.remove(i);
      writer.removeTransaction(tx.getTransactionID());
      assertSame(((ClientTransaction) list.getFirst()).getSequenceID(), writer.getMinTransactionSequence());
    }

    // now remove the leastmost transaction and make sure the min increases.
    for (Iterator i = list.iterator(); i.hasNext();) {
      ClientTransaction tx = (ClientTransaction) i.next();
      assertSame(((ClientTransaction) list.getFirst()).getSequenceID(), writer.getMinTransactionSequence());
      i.remove();
      writer.removeTransaction(tx.getTransactionID());
    }
  }

  public void testSend() throws Exception {
    assertTrue(messageFactory.messages.isEmpty());

    writer.send();
    assertEquals(1, messageFactory.messages.size());
    TestCommitTransactionMessage message = (TestCommitTransactionMessage) messageFactory.messages.get(0);
    assertEquals(1, message.setBatchCalls.size());
    assertSame(writer, message.setBatchCalls.get(0));
    assertEquals(1, message.sendCalls.size());
  }

  public void testWriteRead() throws IOException {
    long sequence = 0;
    ObjectStringSerializer serializer = new ObjectStringSerializer();
    TestCommitTransactionMessageFactory mf = new TestCommitTransactionMessageFactory();
    ClientID clientID = new ClientID(new ChannelID(69));
    TxnBatchID batchID = new TxnBatchID(42);

    List tx1Notifies = new LinkedList();
    // A nested transaction (all this buys us is more than 1 lock in a txn)
    LockID lid1 = new LockID("1");
    TransactionContext tc = new TransactionContextImpl(lid1, TxnType.NORMAL);
    ClientTransaction tmp = new ClientTransactionImpl(new TransactionID(101), new NullRuntimeLogger());
    tmp.setTransactionContext(tc);
    LockID lid2 = new LockID("2");
    tc = new TransactionContextImpl(lid2, TxnType.NORMAL, Arrays.asList(new LockID[] { lid1, lid2 }));
    ClientTransaction txn1 = new ClientTransactionImpl(new TransactionID(1), new NullRuntimeLogger());
    txn1.setTransactionContext(tc);

    txn1.fieldChanged(new MockTCObject(new ObjectID(1), this), "class", "class.field", ObjectID.NULL_ID, -1);
    txn1.createObject(new MockTCObject(new ObjectID(2), this));
    txn1.createRoot("root", new ObjectID(3));
    for (int i = 0; i < 10; i++) {
      Notify notify = new Notify(new LockID("" + i), new ThreadID(i), i % 2 == 0);
      tx1Notifies.add(notify);
      txn1.addNotify(notify);
    }

    tc = new TransactionContextImpl(new LockID("3"), TxnType.CONCURRENT);
    ClientTransaction txn2 = new ClientTransactionImpl(new TransactionID(2), new NullRuntimeLogger());
    txn2.setTransactionContext(tc);

    writer = new TransactionBatchWriter(batchID, serializer, encoding, mf);

    txn1.setSequenceID(new SequenceID(++sequence));
    txn2.setSequenceID(new SequenceID(++sequence));
    writer.addTransaction(txn1);
    writer.addTransaction(txn2);
    writer.wait4AllTxns2Serialize();

    TransactionBatchReaderImpl reader = new TransactionBatchReaderImpl(gidGenerator, writer.getData(), clientID,
                                                                       serializer, new ActiveServerTransactionFactory());
    assertEquals(2, reader.getNumTxns());
    assertEquals(batchID, reader.getBatchID());

    int count = 0;
    ServerTransaction txn;
    while ((txn = reader.getNextTransaction()) != null) {
      count++;
      assertEquals(clientID, txn.getSourceID());
      assertEquals(count, txn.getTransactionID().toLong());

      switch (count) {
        case 1:
          assertEquals(2, txn.getChanges().size());
          assertEquals(1, txn.getNewRoots().size());
          assertEquals("root", txn.getNewRoots().keySet().toArray()[0]);
          assertEquals(new ObjectID(3), txn.getNewRoots().values().toArray()[0]);
          assertEquals(2, txn.getObjectIDs().size());
          assertTrue(txn.getObjectIDs().containsAll(Arrays.asList(new ObjectID[] { new ObjectID(1), new ObjectID(2) })));
          assertEquals(TxnType.NORMAL, txn.getTransactionType());
          assertTrue(Arrays.equals(new LockID[] { new LockID("1"), new LockID("2") }, txn.getLockIDs()));
          assertEquals(tx1Notifies, txn.getNotifies());
          break;
        case 2:
          assertEquals(0, txn.getChanges().size());
          assertEquals(0, txn.getNewRoots().size());
          assertEquals(0, txn.getObjectIDs().size());
          assertEquals(TxnType.CONCURRENT, txn.getTransactionType());
          assertTrue(Arrays.equals(new LockID[] { new LockID("3") }, txn.getLockIDs()));
          break;
        default:
          fail("count is " + count);
      }
    }

    assertEquals(2, count);

  }

}
