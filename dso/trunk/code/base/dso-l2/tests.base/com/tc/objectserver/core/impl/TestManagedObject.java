/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.core.impl;

import com.tc.exception.ImplementMe;
import com.tc.io.TCByteBufferOutputStream;
import com.tc.object.ObjectID;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAException;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.dna.impl.ObjectStringSerializer;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.api.ObjectInstanceMonitor;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.objectserver.impl.ManagedObjectReference;
import com.tc.objectserver.impl.ObjectManagerImpl;
import com.tc.objectserver.managedobject.AbstractManagedObjectState;
import com.tc.objectserver.managedobject.BackReferences;
import com.tc.objectserver.managedobject.ManagedObjectStateFactory;
import com.tc.objectserver.managedobject.ManagedObjectTraverser;
import com.tc.objectserver.mgmt.ManagedObjectFacade;
import com.tc.objectserver.persistence.api.ManagedObjectStore;
import com.tc.util.concurrent.NoExceptionLinkedQueue;

import gnu.trove.TLinkable;

import java.io.ObjectOutput;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * @author steve
 */
public class TestManagedObject implements ManagedObject, ManagedObjectReference, Serializable {
  public final NoExceptionLinkedQueue setTransientStateCalls = new NoExceptionLinkedQueue();
  private final ObjectID              id;
  private final ArrayList<ObjectID>   references;
  public boolean                      isDirty;
  public boolean                      isNew;

  public TestManagedObject(ObjectID id, ArrayList<ObjectID> references) {
    this.id = id;
    this.references = references;
  }

  public TestManagedObject(ObjectID id) {
    this(id, new ArrayList<ObjectID>());
  }

  public void setReference(int index, ObjectID id) {

    if (index < this.references.size()) {
      this.references.set(index, id);
    } else {
      this.references.add(index, id);
    }
  }

  public ObjectID getID() {
    return id;
  }

  public synchronized Set<ObjectID> getObjectReferences() {
    return new HashSet<ObjectID>(references);
  }

  public void apply(DNA dna, TransactionID txID, BackReferences includeIDs, ObjectInstanceMonitor imo) {
    // do nothing
  }

  public void commit() {
    return;
  }

  public void toDNA(TCByteBufferOutputStream out, ObjectStringSerializer serializer) {
    throw new ImplementMe();
  }

  public void setObjectStore(ManagedObjectStore store) {
    return;
  }

  public ManagedObjectFacade createFacade(int limit) {
    throw new ImplementMe();
  }

  public boolean isDirty() {
    return this.isDirty;
  }

  public void setIsDirty(boolean isDirty) {
    this.isDirty = isDirty;
  }

  public synchronized void addReferences(Set<ObjectID> ids) {
    for (Iterator<ObjectID> iter = ids.iterator(); iter.hasNext();) {
      ObjectID oid = iter.next();
      this.references.add(oid);
    }
  }

  public synchronized void addReferences(Set<ObjectID> ids, ObjectManagerImpl[] objectManagers) {
    for (Iterator<ObjectID> iter = ids.iterator(); iter.hasNext();) {
      ObjectID oid = iter.next();
      this.references.add(oid);
      objectManagers[oid.getGroupID()].changed(null, null, oid);
    }
  }

  public synchronized void removeReferences(Set<ObjectID> ids) {
    for (Iterator<ObjectID> iter = ids.iterator(); iter.hasNext();) {
      this.references.remove(iter.next());
    }
  }

  public boolean isNew() {
    return this.isNew;
  }

  public void setTransientState(ManagedObjectStateFactory stateFactory) {
    setTransientStateCalls.put(stateFactory);
  }

  public ManagedObjectReference getReference() {
    return this;
  }

  boolean removeOnRelease;

  public void setRemoveOnRelease(boolean removeOnRelease) {
    this.removeOnRelease = removeOnRelease;
  }

  public boolean isRemoveOnRelease() {
    return removeOnRelease;
  }

  boolean referenced = false;

  public void markReference() {
    referenced = true;
  }

  public void unmarkReference() {
    referenced = false;
  }

  public boolean isReferenced() {
    return referenced;
  }

  public ManagedObject getObject() {
    return this;
  }

  public ObjectID getObjectID() {
    return getID();
  }

  public void markAccessed() {
    throw new ImplementMe();
  }

  public void clearAccessed() {
    throw new ImplementMe();
  }

  public boolean recentlyAccessed() {
    throw new ImplementMe();
  }

  public int accessCount(int accessed) {
    throw new ImplementMe();
  }

  TLinkable next;
  TLinkable previous;

  public TLinkable getNext() {
    return this.next;
  }

  public TLinkable getPrevious() {
    return this.previous;
  }

  public void setNext(TLinkable linkable) {
    this.next = linkable;
  }

  public void setPrevious(TLinkable linkable) {
    this.previous = linkable;
  }

  public ManagedObjectState getManagedObjectState() {
    return new NullManagedObjectState();
  }

  @Override
  public String toString() {
    return "TestManagedObject[" + id + "]";
  }

  public boolean canEvict() {
    return true;
  }

  public void addObjectReferencesTo(ManagedObjectTraverser traverser) {
    traverser.addReachableObjectIDs(getObjectReferences());
  }

  public void apply(DNA dna, TransactionID txnID, BackReferences includeIDs, ObjectInstanceMonitor instanceMonitor,
                    boolean ignoreIfOlderDNA) throws DNAException {
    // TODO: do i need to implement this?
  }

  public long getVersion() {
    throw new ImplementMe();
  }

  public void setIsNew(boolean newFlag) {
    this.isNew = newFlag;
  }

  private class NullManagedObjectState extends AbstractManagedObjectState {
    private final byte type = 0;

    @Override
    protected boolean basicEquals(AbstractManagedObjectState o) {
      throw new UnsupportedOperationException();
    }

    public void addObjectReferencesTo(ManagedObjectTraverser traverser) {
      throw new UnsupportedOperationException();
    }

    public void apply(ObjectID objectID, DNACursor cursor, BackReferences includeIDs) {
      throw new UnsupportedOperationException();
    }

    public ManagedObjectFacade createFacade(ObjectID objectID, String className, int limit) {
      throw new UnsupportedOperationException();
    }

    public void dehydrate(ObjectID objectID, DNAWriter writer) {
      throw new UnsupportedOperationException();
    }

    public String getClassName() {
      throw new UnsupportedOperationException();
    }

    public String getLoaderDescription() {
      throw new UnsupportedOperationException();
    }

    public Set getObjectReferences() {
      throw new UnsupportedOperationException();
    }

    public byte getType() {
      return type;
    }

    public void writeTo(ObjectOutput o) {
      throw new UnsupportedOperationException();
    }
  }

}
