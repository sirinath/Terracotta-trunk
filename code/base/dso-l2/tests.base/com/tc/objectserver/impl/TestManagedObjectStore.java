/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.impl;

import com.tc.exception.ImplementMe;
import com.tc.object.ObjectID;
import com.tc.objectserver.context.GCResultContext;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.persistence.api.ManagedObjectStore;
import com.tc.objectserver.persistence.api.PersistenceTransaction;
import com.tc.text.PrettyPrinter;
import com.tc.util.ObjectIDSet2;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class TestManagedObjectStore implements ManagedObjectStore {

  public boolean       addNewWasCalled = false;
  public boolean       containsKey;
  public ObjectIDSet2  keySet;
  public ManagedObject managedObject;
  private int          count;

  public boolean containsObject(ObjectID id) {
    return containsKey;
  }

  public void addNewObject(ManagedObject managed) {
    addNewWasCalled = true;
    count++;
  }

  public ObjectIDSet2 getAllObjectIDs() {
    return keySet;
  }

  public int getObjectCount() {
    return count;
  }

  public ManagedObject getObjectByID(ObjectID id) {
    return managedObject;
  }

  public void commitObject(PersistenceTransaction tx, ManagedObject object) {
    return;
  }

  public void commitAllObjects(PersistenceTransaction tx, Collection c) {
    return;
  }

  public PrettyPrinter prettyPrint(PrettyPrinter out) {
    return out.print(getClass().getName());
  }

  public void removeAllObjectsByIDNow(PersistenceTransaction tx, SortedSet<ObjectID> objectIds) {
    count -= objectIds.size();
    return;
  }

  public void removeAllObjectsByID(GCResultContext gcResult) {
    removeAllObjectsByIDNow(null, new TreeSet(gcResult.getGCedObjectIDs()));
  }

  public void shutdown() {
    return;
  }

  public boolean inShutdown() {
    return false;
  }

  public ObjectID getRootID(String name) {
    return null;
  }

  public Set getRoots() {
    return null;
  }

  public Set getRootNames() {
    return null;
  }

  public void addNewRoot(PersistenceTransaction tx, String rootName, ObjectID id) {
    return;
  }

  public long nextObjectIDBatch(int batchSize) {
    throw new ImplementMe();
  }

  public void setNextAvailableObjectID(long startID) {
    throw new ImplementMe();
  }

  public Map getRootNamesToIDsMap() {
    return null;
  }

}