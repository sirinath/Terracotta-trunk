/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.api;

import com.tc.object.ObjectID;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.objectserver.persistence.sleepycat.PersistableCollection;
import com.tc.objectserver.persistence.sleepycat.SleepycatCollectionFactory;

public class PersistentCollectionsUtil {

  public static boolean isPersistableCollectionType(byte type) {
    switch (type) {
      case ManagedObjectState.MAP_TYPE:
      case ManagedObjectState.PARTIAL_MAP_TYPE:
      case ManagedObjectState.TREE_MAP_TYPE:
      case ManagedObjectState.CONCURRENT_HASHMAP_TYPE:
      case ManagedObjectState.SET_TYPE:
      case ManagedObjectState.TREE_SET_TYPE:
      // XXX: This is a rather ugly hack to get around the requirements of tim-concurrent-collections.
      case ManagedObjectState.CONCURRENT_STRING_MAP_TYPE:
        return true;
      default:
        return false;
    }
  }

  public static PersistableCollection createPersistableCollection(ObjectID id, SleepycatCollectionFactory collectionFactory,
                                                            byte type) {
    switch (type) {
      case ManagedObjectState.MAP_TYPE:
      case ManagedObjectState.PARTIAL_MAP_TYPE:
      case ManagedObjectState.TREE_MAP_TYPE:
      case ManagedObjectState.CONCURRENT_HASHMAP_TYPE:
      // XXX: This is a rather ugly hack to get around the requirements of tim-concurrent-collections.
      case ManagedObjectState.CONCURRENT_STRING_MAP_TYPE:
        return (PersistableCollection) collectionFactory.createPersistentMap(id);
      case ManagedObjectState.SET_TYPE:
      case ManagedObjectState.TREE_SET_TYPE:
        return (PersistableCollection) collectionFactory.createPersistentSet(id);
      default:
        return null;
    }
  }

}
