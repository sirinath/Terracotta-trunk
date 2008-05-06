/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.objectserver.persistence.impl;

import com.tc.objectserver.persistence.api.PersistenceTransaction;
import com.tc.objectserver.persistence.api.PersistenceTransactionProvider;

public class NullPersistenceTransactionProvider implements PersistenceTransactionProvider {

  private static final PersistenceTransaction NULL_TRANSACTION = new NullPersistenceTransaction();

  public PersistenceTransaction newTransaction() {
    return NULL_TRANSACTION;
  }

  public PersistenceTransaction nullTransaction() {
    return NULL_TRANSACTION;
  }

  private final static class NullPersistenceTransaction implements PersistenceTransaction {
    public void commit() {
      return;
    }

    public Object getProperty(Object key) {
      return null;
    }

    public Object setProperty(Object key, Object value) {
      return null;
    }
  }

}
