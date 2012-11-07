/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.util;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.objectserver.managedobject.ManagedObjectChangeListener;
import com.tc.objectserver.managedobject.ManagedObjectChangeListenerProvider;
import com.tc.objectserver.managedobject.ManagedObjectStateFactory;
import com.tc.objectserver.managedobject.NullManagedObjectChangeListener;
import com.tc.objectserver.persistence.HeapStorageManagerFactory;
import com.tc.objectserver.persistence.Persistor;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

public abstract class BaseUtility {

  private static final TCLogger logger = TCLogging.getLogger(BaseUtility.class);

  protected final Writer        writer;
  protected final Map           dbPersistorsMap;
  protected final Map           dbEnvironmentsMap;
  protected final File[]        databaseDirs;

  public BaseUtility(Writer writer, File[] databaseDirs) throws Exception {
    this.writer = writer;
    this.databaseDirs = databaseDirs;
    dbPersistorsMap = new HashMap(databaseDirs.length);
    dbEnvironmentsMap = new HashMap(databaseDirs.length);
    initPersistors(databaseDirs.length);
  }

  private void initPersistors(int persistorCount) throws Exception {
    ManagedObjectStateFactory.disableSingleton(true);
    for (int i = 1; i <= persistorCount; i++) {
      dbPersistorsMap.put(Integer.valueOf(i), createPersistor(i));
    }
  }

  private Persistor createPersistor(int id) throws Exception {
    final TestManagedObjectChangeListenerProvider managedObjectChangeListenerProvider = new TestManagedObjectChangeListenerProvider();
    Persistor persistor = new Persistor(HeapStorageManagerFactory.INSTANCE);
    
    return persistor;
  }

  protected Persistor getPersistor(int id) {
    return (Persistor) dbPersistorsMap.get(Integer.valueOf(id));
  }

  protected File getDatabaseDir(int id) {
    return databaseDirs[id - 1];
  }

  protected void log(String message) {
    try {
      writer.write(message);
      writer.write("\n");
      writer.flush();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static class TestManagedObjectChangeListenerProvider implements ManagedObjectChangeListenerProvider {

    public ManagedObjectChangeListener getListener() {
      return new NullManagedObjectChangeListener();

    }
  }

}