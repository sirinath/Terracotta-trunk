package com.tc.objectserver.persistence;

import org.terracotta.corestorage.KeyValueStorageConfig;
import org.terracotta.corestorage.StorageManager;
import org.terracotta.corestorage.monitoring.MonitoredResource;

import com.tc.object.persistence.api.PersistentMapStore;
import com.tc.util.sequence.MutableSequence;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * @author tim
 */
public class Persistor {
  private static final String GLOBAL_TRANSACTION_ID_SEQUENCE = "global_transaction_id_sequence";

  private final StorageManager storageManager;

  private volatile boolean started = false;

  private final PersistentMapStore persistentMapStore;
  private final PersistentObjectFactory persistentObjectFactory;
  private final PersistenceTransactionProvider persistenceTransactionProvider;

  private TransactionPersistor transactionPersistor;
  private ManagedObjectPersistor managedObjectPersistor;
  private MutableSequence gidSequence;
  private ClientStatePersistor clientStatePersistor;
  private SequenceManager sequenceManager;
  private ObjectIDSetMaintainer objectIDSetMaintainer;

  public Persistor(StorageManagerFactory storageManagerFactory) {
    objectIDSetMaintainer = new ObjectIDSetMaintainer();
    try {
      storageManager = storageManagerFactory.createStorageManager(getDataStorageConfigs(objectIDSetMaintainer, storageManagerFactory),
          new SingletonTransformerLookup(Object.class, LiteralSerializer.INSTANCE));
    } catch (IOException e) {
      throw new AssertionError(e);
    }

    persistenceTransactionProvider = new PersistenceTransactionProvider(storageManager);
    persistentObjectFactory = new PersistentObjectFactory(storageManager, storageManagerFactory);
    persistentMapStore = new PersistentMapStoreImpl(storageManager);
  }

  protected StorageManager getStorageManager() {
    return storageManager;
  }

  private Map<String, KeyValueStorageConfig<?, ?>> getDataStorageConfigs(ObjectIDSetMaintainer objectIDSetMaintainer, StorageManagerFactory storageManagerFactory) {
    Map<String, KeyValueStorageConfig<?, ?>> configs = new HashMap<String, KeyValueStorageConfig<?, ?>>();
    TransactionPersistor.addConfigsTo(configs);
    ClientStatePersistor.addConfigsTo(configs);
    ManagedObjectPersistor.addConfigsTo(configs, objectIDSetMaintainer, storageManagerFactory);
    SequenceManager.addConfigsTo(configs);
    return configs;
  }

  public void start() {
    try {
      storageManager.start().get();
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    sequenceManager = new SequenceManager(storageManager);
    transactionPersistor = new TransactionPersistor(storageManager);
    clientStatePersistor = new ClientStatePersistor(sequenceManager, storageManager);
    managedObjectPersistor = new ManagedObjectPersistor(storageManager, sequenceManager, objectIDSetMaintainer);

    gidSequence = sequenceManager.getSequence(GLOBAL_TRANSACTION_ID_SEQUENCE);

    started = true;
  }

  public void close() {
    storageManager.close();
  }
  
  public MonitoredResource getMonitoredResource() {
    checkStarted();
    Collection<MonitoredResource> list = storageManager.getMonitoredResources();
    for (MonitoredResource rsrc : list) {
      if (rsrc.getType() == MonitoredResource.Type.OFFHEAP || rsrc.getType() == MonitoredResource.Type.HEAP) {
        return rsrc;
      }
    }
    return null;
  }

  public PersistenceTransactionProvider getPersistenceTransactionProvider() {
    checkStarted();
    return persistenceTransactionProvider;
  }

  public ClientStatePersistor getClientStatePersistor() {
    checkStarted();
    return clientStatePersistor;
  }

  public ManagedObjectPersistor getManagedObjectPersistor() {
    checkStarted();
    return managedObjectPersistor;
  }

  public TransactionPersistor getTransactionPersistor() {
    checkStarted();
    return transactionPersistor;
  }

  public MutableSequence getGlobalTransactionIDSequence() {
    checkStarted();
    return gidSequence;
  }

  public PersistentMapStore getPersistentStateStore() {
    return persistentMapStore;
  }

  public PersistentObjectFactory getPersistentObjectFactory() {
    checkStarted();
    return persistentObjectFactory;
  }

  public SequenceManager getSequenceManager() {
    checkStarted();
    return sequenceManager;
  }

  private void checkStarted() {
    if (!started) {
      throw new IllegalStateException("Persistor is not yet started.");
    }
  }

  public void backup(File path) throws IOException {
    throw new UnsupportedOperationException("Can not backup a non-persistent L2.");
  }
}
