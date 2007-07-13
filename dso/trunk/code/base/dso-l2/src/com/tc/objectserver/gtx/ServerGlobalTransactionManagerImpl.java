/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.gtx;

import com.tc.net.protocol.tcm.ChannelID;
import com.tc.object.gtx.GlobalTransactionID;
import com.tc.object.tx.ServerTransactionID;
import com.tc.objectserver.persistence.api.PersistenceTransaction;
import com.tc.objectserver.persistence.api.PersistenceTransactionProvider;
import com.tc.objectserver.persistence.api.TransactionStore;
import com.tc.util.SequenceValidator;
import com.tc.util.sequence.Sequence;

import java.util.Collection;
import java.util.Set;

public class ServerGlobalTransactionManagerImpl implements ServerGlobalTransactionManager {

  private final TransactionStore                    transactionStore;
  private final PersistenceTransactionProvider      persistenceTransactionProvider;
  private final SequenceValidator                   sequenceValidator;
  private final GlobalTransactionIDSequenceProvider gidSequenceProvider;
  private final Sequence                            globalTransactionIDSequence;

  public ServerGlobalTransactionManagerImpl(SequenceValidator sequenceValidator, TransactionStore transactionStore,
                                            PersistenceTransactionProvider ptxp,
                                            GlobalTransactionIDSequenceProvider gidSequenceProvider,
                                            Sequence globalTransactionIDSequence) {
    this.sequenceValidator = sequenceValidator;
    this.transactionStore = transactionStore;
    this.persistenceTransactionProvider = ptxp;
    this.gidSequenceProvider = gidSequenceProvider;
    this.globalTransactionIDSequence = globalTransactionIDSequence;
  }

  public void shutdownClient(ChannelID channelID) {
    this.sequenceValidator.remove(channelID);
    PersistenceTransaction tx = this.persistenceTransactionProvider.newTransaction();
    transactionStore.shutdownClient(tx, channelID);
    tx.commit();
  }

  public void shutdownAllClientsExcept(Set cids) {
    PersistenceTransaction tx = this.persistenceTransactionProvider.newTransaction();
    transactionStore.shutdownAllClientsExcept(tx, cids);
    tx.commit();
  }

  public boolean initiateApply(ServerTransactionID stxID) {
    GlobalTransactionDescriptor gtx = this.transactionStore.getTransactionDescriptor(stxID);
    return gtx.initiateApply();
  }

  public void completeTransactions(PersistenceTransaction tx, Collection collection) {
    if (collection.isEmpty()) return;
    transactionStore.removeAllByServerTransactionID(tx, collection);
  }

  public void commit(PersistenceTransaction persistenceTransaction, ServerTransactionID stxID) {
    transactionStore.commitTransactionDescriptor(persistenceTransaction, stxID);
  }

  public void commitAll(PersistenceTransaction persistenceTransaction, Collection stxIDs) {
    transactionStore.commitAllTransactionDescriptor(persistenceTransaction, stxIDs);
  }

  public GlobalTransactionID getLowGlobalTransactionIDWatermark() {
    return transactionStore.getLeastGlobalTransactionID();
  }

  public GlobalTransactionID getOrCreateGlobalTransactionID(ServerTransactionID serverTransactionID) {
    GlobalTransactionDescriptor gdesc = transactionStore.getOrCreateTransactionDescriptor(serverTransactionID);
    return gdesc.getGlobalTransactionID();
  }

  public GlobalTransactionID getGlobalTransactionID(ServerTransactionID serverTransactionID) {
    GlobalTransactionDescriptor gdesc = transactionStore.getTransactionDescriptor(serverTransactionID);
    return (gdesc != null ? gdesc.getGlobalTransactionID() : GlobalTransactionID.NULL_ID);

  }

  public void createGlobalTransactionDescIfNeeded(ServerTransactionID stxnID, GlobalTransactionID globalTransactionID) {
    transactionStore.createGlobalTransactionDescIfNeeded(stxnID, globalTransactionID);
  }

  public GlobalTransactionIDSequenceProvider getGlobalTransactionIDSequenceProvider() {
    return gidSequenceProvider;
  }

  public Sequence getGlobalTransactionIDSequence() {
    return globalTransactionIDSequence;
  }

}
