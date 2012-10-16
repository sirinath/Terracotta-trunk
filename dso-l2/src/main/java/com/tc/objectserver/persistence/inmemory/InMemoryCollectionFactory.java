/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.inmemory;

import com.tc.object.ObjectID;
import com.tc.objectserver.persistence.api.PersistentCollectionFactory;
import com.tc.objectserver.persistence.db.PersistableCollection;
import com.tc.objectserver.persistence.db.TCCollectionsSerializer;
import com.tc.objectserver.storage.api.PersistenceTransaction;
import com.tc.objectserver.storage.api.TCMapsDatabase;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InMemoryCollectionFactory implements PersistentCollectionFactory {

  @Override
  public Map createPersistentMap(ObjectID id) {
    return new InMemoryPersistableMap();
  }

  @Override
  public Set createPersistentSet(ObjectID id) {
    return new InMemoryPersistableSet();
  }

  private static class InMemoryPersistableMap extends HashMap implements PersistableCollection {
    public InMemoryPersistableMap() {
      super(0);
    }

    @Override
    public int commit(TCCollectionsSerializer serializer, PersistenceTransaction tx, TCMapsDatabase db) {
      // do nothing
      return 0;
    }

    @Override
    public void load(TCCollectionsSerializer serializer, PersistenceTransaction tx, TCMapsDatabase db) {
      // do nothing
    }

  }

  private static class InMemoryPersistableSet extends HashSet implements PersistableCollection {
    public InMemoryPersistableSet() {
      super(0);
    }

    @Override
    public int commit(TCCollectionsSerializer serializer, PersistenceTransaction tx, TCMapsDatabase db) {
      // do nothing
      return 0;
    }

    @Override
    public void load(TCCollectionsSerializer serializer, PersistenceTransaction tx, TCMapsDatabase db) {
      // do nothing
    }

  }

}
