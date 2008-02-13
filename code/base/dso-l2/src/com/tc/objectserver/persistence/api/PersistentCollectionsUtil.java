/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.api;

import com.tc.objectserver.core.api.ManagedObjectState;

public class PersistentCollectionsUtil {

  public static boolean isPersistableCollectionType(byte type) {
    switch (type) {
      case ManagedObjectState.MAP_TYPE:
      case ManagedObjectState.PARTIAL_MAP_TYPE:
      case ManagedObjectState.TREE_MAP_TYPE:
      case ManagedObjectState.CONCURRENT_HASHMAP_TYPE:
        return true;
      default:
        return false;
    }

    // unreachable
  }

}
