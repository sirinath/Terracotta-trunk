/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object;

import com.tc.exception.ImplementMe;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNAException;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.util.ToggleableStrongReference;

import gnu.trove.TLinkable;

import java.util.LinkedList;
import java.util.List;

/**
 * Mock implementation of TCObject for testing.
 */
public class MockTCObject implements TCObject {
  private ObjectID         id;
  private Object           peer;
  private List             history          = new LinkedList();
  private final Object     resolveLock      = new Object();
  private long             version          = 0;
  private TCClass          tcClazz;
  private boolean          accessed         = false;
  private boolean          isNew            = false;
  private RuntimeException hydrateException = null;

  public MockTCObject(final ObjectID id, final Object obj) {
    this(id, obj, false, false);
  }

  public MockTCObject(final ObjectID id, final Object obj, boolean isIndexed, boolean isLogical) {
    this.peer = obj;
    this.id = id;
    this.tcClazz = new MockTCClass(isIndexed, isLogical);
  }

  public List getHistory() {
    return history;
  }

  public ObjectID getObjectID() {
    return this.id;
  }

  public Object getPeerObject() {
    return this.peer;
  }

  public TCClass getTCClass() {
    return this.tcClazz;
  }

  public void booleanFieldChanged(String classname, String fieldname, boolean newValue, int index) {
    if ("java.lang.reflect.AccessibleObject.override".equals(fieldname)) {
      // do nothing since the support for AccessibleObject looks up the
      // TCObject instance in the currently active ClientObjectManager, which causes
      // and exception to be thrown during the tests since their accessible status is
      // always set to 'true' before execution
    } else {
      throw new ImplementMe();
    }
  }

  public void byteFieldChanged(String classname, String fieldname, byte newValue, int index) {
    throw new ImplementMe();
  }

  public void charFieldChanged(String classname, String fieldname, char newValue, int index) {
    throw new ImplementMe();
  }

  public void doubleFieldChanged(String classname, String fieldname, double newValue, int index) {
    throw new ImplementMe();
  }

  public void floatFieldChanged(String classname, String fieldname, float newValue, int index) {
    throw new ImplementMe();
  }

  public void intFieldChanged(String classname, String fieldname, int newValue, int index) {
    throw new ImplementMe();
  }

  public void longFieldChanged(String classname, String fieldname, long newValue, int index) {
    throw new ImplementMe();
  }

  public void shortFieldChanged(String classname, String fieldname, short newValue, int index) {
    throw new ImplementMe();
  }

  public void setHydrateException(RuntimeException hydrateException) {
    this.hydrateException = hydrateException;
  }

  public void hydrate(DNA from, boolean force) throws DNAException {
    if (hydrateException != null) { throw hydrateException; }
    // nothing
  }

  public void resolveReference(String fieldName) {
    throw new ImplementMe();
  }

  public void resolveArrayReference(int index) {
    return;
  }

  public void objectFieldChanged(String classname, String fieldname, Object newValue, int index) {
    return;
  }

  public static class MethodCall {
    public int      method;
    public Object[] parameters;

    public MethodCall(int method, Object[] parameters) {
      this.method = method;
      this.parameters = parameters;
    }

    public String toString() {
      StringBuffer sb = new StringBuffer();
      sb.append(method);
      sb.append("(");
      for (int i = 0; i < parameters.length; i++) {
        sb.append(parameters[i].toString());
        if (i + 1 < parameters.length) {
          sb.append(',');
        }
      }
      sb.append(")");
      return sb.toString();
    }
  }

  public ObjectID setReference(String fieldName, ObjectID id) {
    throw new ImplementMe();
  }

  public void setArrayReference(int index, ObjectID id) {
    throw new ImplementMe();
  }

  public void setValue(String fieldName, Object obj) {
    throw new ImplementMe();
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  public int clearReferences(int toClear) {
    return 0;
  }

  public Object getResolveLock() {
    return resolveLock;
  }

  public void setNext(TLinkable link) {
    throw new ImplementMe();
  }

  public void setPrevious(TLinkable link) {
    throw new ImplementMe();
  }

  public TLinkable getNext() {
    return null;
  }

  public TLinkable getPrevious() {
    return null;
  }

  public void markAccessed() {
    this.accessed = true;
  }

  public void clearAccessed() {
    this.accessed = false;
  }

  public boolean recentlyAccessed() {
    return this.accessed;
  }

  public int accessCount(int factor) {
    throw new ImplementMe();
  }

  public void clearReference(String fieldName) {
    throw new ImplementMe();
  }

  public void resolveAllReferences() {
    //
  }

  public boolean isNew() {
    return isNew;
  }

  public void setNew(boolean isNew) {
    this.isNew = isNew;
  }

  public boolean isShared() {
    return true;
  }

  public void objectFieldChangedByOffset(String classname, long fieldOffset, Object newValue, int index) {
    return;
  }

  public void logicalInvoke(int method, String methodSignature, Object[] params) {
    history.add(new MethodCall(method, params));
  }

  public String getFieldNameByOffset(long fieldOffset) {
    throw new ImplementMe();
  }

  public void disableAutoLocking() {
    throw new ImplementMe();
  }

  public boolean autoLockingDisabled() {
    return false;
  }

  public boolean canEvict() {
    throw new ImplementMe();
  }

  public void objectArrayChanged(int startPos, Object[] array, int length) {
    throw new ImplementMe();
  }

  public void primitiveArrayChanged(int startPos, Object array, int length) {
    throw new ImplementMe();
  }

  public void literalValueChanged(Object newValue, Object oldValue) {
    throw new ImplementMe();
  }

  public void setLiteralValue(Object newValue) {
    throw new ImplementMe();
  }

  public ArrayIndexOutOfBoundsException checkArrayIndex(int index) {
    throw new ImplementMe();
  }

  public boolean isFieldPortableByOffset(long fieldOffset) {
    throw new ImplementMe();
  }

  public ToggleableStrongReference getOrCreateToggleRef() {
    throw new ImplementMe();
  }

  public void setNotNew() {
    isNew = false;
  }

  public void dehydrate(DNAWriter writer) {
    //
  }
}
