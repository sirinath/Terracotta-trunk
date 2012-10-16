/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.db;

import com.tc.objectserver.persistence.api.StringIndexPersistor;
import com.tc.objectserver.persistence.db.DBPersistorImpl.DBPersistorBase;
import com.tc.objectserver.storage.api.PersistenceTransaction;
import com.tc.objectserver.storage.api.PersistenceTransactionProvider;
import com.tc.objectserver.storage.api.TCLongToStringDatabase;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StringIndexPersistorImpl extends DBPersistorBase implements StringIndexPersistor {

  private final PersistenceTransactionProvider ptp;
  private final TCLongToStringDatabase         stringIndexDatabase;
  private final AtomicBoolean                  initialized = new AtomicBoolean(false);

  public StringIndexPersistorImpl(PersistenceTransactionProvider ptp, TCLongToStringDatabase stringIndexDatabase) {
    this.ptp = ptp;
    this.stringIndexDatabase = stringIndexDatabase;
  }

  public Map<Long, String> loadMappingsInto(Map<Long, String> target) {
    if (initialized.getAndSet(true)) throw new AssertionError("Attempt to use more than once.");
    PersistenceTransaction tx = ptp.newTransaction();
    return stringIndexDatabase.loadMappingsInto(target, tx);
  }

  public void saveMapping(long index, String string) {
    PersistenceTransaction tx = null;
    try {
      tx = ptp.newTransaction();
      stringIndexDatabase.insert(index, string, tx);
      tx.commit();
    } catch (Throwable t) {
      abortOnError(tx);
      throw new DBException(t);
    }
  }
}