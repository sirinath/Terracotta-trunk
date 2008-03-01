/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object;

import com.tc.exception.ImplementMe;
import com.tc.exception.TCNonPortableObjectError;
import com.tc.object.appevent.ApplicationEvent;
import com.tc.object.appevent.ApplicationEventContext;
import com.tc.object.bytecode.Manageable;
import com.tc.object.dna.api.DNA;
import com.tc.object.tx.ClientTransactionManager;
import com.tc.object.tx.optimistic.OptimisticTransactionManager;
import com.tc.object.util.ToggleableStrongReference;
import com.tc.text.PrettyPrinter;
import com.tc.util.Assert;

import java.io.Writer;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * @author steve
 */
public class TestClientObjectManager implements ClientObjectManager {
  public final Map                 objects         = new HashMap();
  public final Map                 object2TCObject = new IdentityHashMap();
  private int                      idSequence      = 1;
  private Object                   root            = new IdentityHashMap();
  private boolean                  isManaged;
  private ReferenceQueue           referenceQueue;
  private ClientTransactionManager txManager;

  public void add(Object id, TCObject tc) {
    objects.put(id, tc);
    this.object2TCObject.put(tc.getPeerObject(), tc);
  }

  public void setIsManaged(boolean b) {
    this.isManaged = b;
  }

  public boolean getIsManaged() {
    return this.isManaged;
  }


  public boolean isManaged(Object pojo) {
    return this.object2TCObject.containsKey(pojo) || isManaged;
  }

  public boolean isPortableInstance(Object pojo) {
    return true;
  }

  public boolean isPortableClass(Class clazz) {
    return true;
  }

  public void sharedIfManaged(Object pojo) {
    if (isManaged(pojo)) {
      lookupOrCreate(pojo);
    }
  }

  public synchronized TCObject lookupOrCreate(Object obj) {
    // System.out.println(this + ".lookupOrCreate(" + obj + ")");
    TCObject rv = lookup(obj);
    if (rv == null) {
      rv = new MockTCObject(new ObjectID(idSequence++), obj);
      object2TCObject.put(obj, rv);
      if (obj instanceof Manageable) {
        ((Manageable) obj).__tc_managed(rv);
      }
    }
    return rv;
  }

  private synchronized TCObject lookup(Object obj) {
    TCObject rv = (TCObject) object2TCObject.get(obj);
    return rv;
  }

  public Object lookupOrCreateRoot(String name, Object candidate) {
    Object rv = null;
    if (candidate == null) {
      rv = this.root;
    } else {
      rv = candidate;
    }
    Assert.assertNotNull(rv);
    return rv;
  }

  public TCObject lookupIfLocal(ObjectID id) {
    throw new ImplementMe();
  }

  public Collection getAllObjectIDsAndClear(Collection c) {
    c.addAll(objects.keySet());
    return c;
  }

  public TCObject lookup(ObjectID id) {
    System.out.println(this + ".lookup(" + id + ")");
    return (TCObject) objects.get(id);
  }

  public WeakReference createNewPeer(TCClass clazz, int size, ObjectID id, ObjectID parentID) {
    throw new ImplementMe();
  }

  public Object lookupObject(ObjectID id) {
    return ((TCObject) objects.get(id)).getPeerObject();
  }

  public Object lookupObject(ObjectID id, ObjectID parentContext) {
    return ((TCObject) objects.get(id)).getPeerObject();
  }

  public TCClass getOrCreateClass(Class clazz) {
    throw new ImplementMe();
  }

  public void setTransactionManager(ClientTransactionManager txManager) {
    this.txManager = txManager;
  }

  public void setReferenceQueue(ReferenceQueue rq) {
    this.referenceQueue = rq;
  }

  public ReferenceQueue getReferenceQueue() {
    return this.referenceQueue;
  }

  public ObjectID lookupExistingObjectID(Object obj) {
    return ((TCObject) this.object2TCObject.get(obj)).getObjectID();
  }

  public void markReferenced(TCObject tcobj) {
    // mark referenced
  }

  public void shutdown() {
    //
  }

  public ClientTransactionManager getTransactionManager() {
    return txManager;
  }

  public Class getClassFor(String className, String loaderDesc) {
    throw new ImplementMe();
  }

  public TCObject lookupExistingOrNull(Object pojo) {
//    if (isManaged) {
//      lookupOrCreate(pojo);
//    }

    return (TCObject) this.object2TCObject.get(pojo);
  }

  public Object lookupRoot(String name) {
    throw new ImplementMe();
  }

  public void unpause() {
    return;
  }

  public void pause() {
    return;
  }

  public void starting() {
    return;
  }

  public void checkPortabilityOfField(Object value, String fieldName, Object pojo) throws TCNonPortableObjectError {
    return;
  }

  public void checkPortabilityOfLogicalAction(Object[] params, int index, String methodName, Object pojo)
      throws TCNonPortableObjectError {
    return;
  }

  public WeakReference createNewPeer(TCClass clazz, DNA dna) {
    throw new ImplementMe();
  }

  public Object deepCopy(Object source, OptimisticTransactionManager optimisticTxManager) {
    throw new ImplementMe();
  }

  public Object createNewCopyInstance(Object source, Object parent) {
    throw new ImplementMe();
  }

  public Object createParentCopyInstanceIfNecessary(Map visited, Map cloned, Object v) {
    throw new ImplementMe();
  }

  public void replaceRootIDIfNecessary(String rootName, ObjectID newRootID) {
    throw new ImplementMe();
  }

  public Object lookupOrCreateRoot(String name, Object obj, boolean dsoFinal) {
    throw new ImplementMe();
  }

  public TCObject lookupOrShare(Object pojo) {
    throw new ImplementMe();
  }

  public boolean isCreationInProgress() {
    return false;
  }

  public void addPendingCreateObjectsToTransaction() {
    // do nothing
  }

  public boolean hasPendingCreateObjects() {
    return false;
  }

  public Object lookupObjectNoDepth(ObjectID id) {
    throw new ImplementMe();
  }

  public Object lookupOrCreateRootNoDepth(String rootName, Object object) {
    throw new ImplementMe();
  }

  public Object createOrReplaceRoot(String rootName, Object r) {
    throw new ImplementMe();
  }

  public void storeObjectHierarchy(Object pojo, ApplicationEventContext context) {
    throw new ImplementMe();
  }

  public void sendApplicationEvent(Object pojo, ApplicationEvent event) {
    throw new ImplementMe();
  }

  public Object cloneAndInvokeLogicalOperation(Object pojo, String methodName, Object[] parameters) {
    throw new ImplementMe();
  }

  public ToggleableStrongReference getOrCreateToggleRef(ObjectID id, Object peer) {
    throw new ImplementMe();
  }

  public String dump() {
    throw new ImplementMe();
  }

  public void dump(Writer writer) {
    throw new ImplementMe();
    
  }

  public void dumpToLogger() {
    throw new ImplementMe();
    
  }

  public PrettyPrinter prettyPrint(PrettyPrinter out) {
    throw new ImplementMe();
  }

}

