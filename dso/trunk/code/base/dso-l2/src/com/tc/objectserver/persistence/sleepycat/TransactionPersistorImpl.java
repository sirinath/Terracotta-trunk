/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.sleepycat;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.tc.object.gtx.GlobalTransactionID;
import com.tc.object.tx.ServerTransactionID;
import com.tc.objectserver.gtx.GlobalTransactionDescriptor;
import com.tc.objectserver.persistence.api.PersistenceTransaction;
import com.tc.objectserver.persistence.api.PersistenceTransactionProvider;
import com.tc.objectserver.persistence.api.TransactionPersistor;
import com.tc.objectserver.persistence.sleepycat.SleepycatPersistor.SleepycatPersistorBase;
import com.tc.util.Conversion;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.SortedSet;

class TransactionPersistorImpl extends SleepycatPersistorBase implements TransactionPersistor {

  private final Database                       db;
  private final CursorConfig                   cursorConfig;
  private final PersistenceTransactionProvider ptp;

  public TransactionPersistorImpl(Database db, PersistenceTransactionProvider ptp) {
    this.db = db;
    this.ptp = ptp;
    this.cursorConfig = new CursorConfig();
    this.cursorConfig.setReadCommitted(true);
  }

  public Collection loadAllGlobalTransactionDescriptors() {
    Cursor cursor = null;
    PersistenceTransaction tx = null;
    try {
      Collection rv = new HashSet();
      tx = ptp.newTransaction();
      cursor = this.db.openCursor(pt2nt(tx), cursorConfig);
      DatabaseEntry key = new DatabaseEntry();
      DatabaseEntry value = new DatabaseEntry();
      while (OperationStatus.SUCCESS.equals(cursor.getNext(key, value, LockMode.DEFAULT))) {
        rv.add(new GlobalTransactionDescriptor(bytes2ServerTxnID(key.getData()), bytes2GlobalTxnID(value.getData())));
      }
      cursor.close();
      tx.commit();
      return rv;
    } catch (DatabaseException e) {
      abortOnError(cursor, tx);
      throw new DBException(e);
    }
  }

  public void saveGlobalTransactionDescriptor(PersistenceTransaction tx, GlobalTransactionDescriptor gtx) {
    DatabaseEntry key = new DatabaseEntry();
    DatabaseEntry value = new DatabaseEntry();
    key.setData(serverTxnID2Bytes(gtx.getServerTransactionID()));
    value.setData(globalTxnID2Bytes(gtx.getGlobalTransactionID()));
    try {
      this.db.put(pt2nt(tx), key, value);
    } catch (DatabaseException e) {
      throw new DBException(e);
    }
  }

  private GlobalTransactionID bytes2GlobalTxnID(byte[] data) {
    return new GlobalTransactionID(Conversion.bytes2Long(data));
  }

  private byte[] globalTxnID2Bytes(GlobalTransactionID globalTransactionID) {
    return Conversion.long2Bytes(globalTransactionID.toLong());
  }

  private byte[] serverTxnID2Bytes(ServerTransactionID serverTransactionID) {
    return serverTransactionID.getBytes();
  }

  private ServerTransactionID bytes2ServerTxnID(byte[] data) {
    return ServerTransactionID.createFrom(data);
  }

  public void deleteAllGlobalTransactionDescriptors(PersistenceTransaction tx, SortedSet<ServerTransactionID> serverTxnIDs) {
    DatabaseEntry key = new DatabaseEntry();
    Transaction txn = pt2nt(tx);
    for (Iterator i = serverTxnIDs.iterator(); i.hasNext();) {
      ServerTransactionID stxID = (ServerTransactionID) i.next();
      key.setData(serverTxnID2Bytes(stxID));
      try {
        db.delete(txn, key);
      } catch (DatabaseException e) {
        throw new DBException(e);
      }
    }
  }
}