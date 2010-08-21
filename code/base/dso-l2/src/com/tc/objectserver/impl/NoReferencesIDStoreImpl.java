/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.impl;

import com.tc.object.ObjectID;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.ObjectIDSet;
import com.tc.util.ObjectIDSet.ObjectIDSetType;

public class NoReferencesIDStoreImpl implements NoReferencesIDStore {

  private static final boolean      FAULTING_OPTIMIZATION = TCPropertiesImpl
                                                              .getProperties()
                                                              .getBoolean(
                                                                          TCPropertiesConsts.L2_OBJECTMANAGER_DGC_FAULTING_OPTIMIZATION,
                                                                          true);
  private final NoReferencesIDStore delegate;

  public NoReferencesIDStoreImpl() {
    if (FAULTING_OPTIMIZATION) {
      this.delegate = new OidSetStore();
    } else {
      this.delegate = NoReferencesIDStore.NULL_NO_REFERENCES_ID_STORE;
    }
  }

  public void addToNoReferences(final ManagedObject mo) {
    this.delegate.addToNoReferences(mo);
  }

  public void clearFromNoReferencesStore(final ObjectID id) {
    this.delegate.clearFromNoReferencesStore(id);
  }

  public boolean hasNoReferences(final ObjectID id) {
    return this.delegate.hasNoReferences(id);
  }

  public class OidSetStore implements NoReferencesIDStore {

    private final ObjectIDSet store = new ObjectIDSet(ObjectIDSetType.BITSET_BASED_SET);

    public void addToNoReferences(final ManagedObject mo) {
      if (mo.getManagedObjectState().hasNoReferences()) {
        synchronized (this) {
          this.store.add(mo.getID());
        }
      }
    }

    public synchronized void clearFromNoReferencesStore(final ObjectID id) {
      this.store.remove(id);
    }

    public synchronized boolean hasNoReferences(final ObjectID id) {
      return this.store.contains(id);
    }

  }

}
