/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object;

import bsh.EvalError;
import bsh.Interpreter;
import bsh.ParseException;
import bsh.TargetError;

import com.tc.lang.TCThreadGroup;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.bytecode.Manageable;
import com.tc.object.bytecode.TransparentAccess;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNAException;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.field.TCField;
import com.tc.object.util.ToggleableStrongReference;
import com.tc.util.Assert;
import com.tc.util.Conversion;
import com.tc.util.Util;

import gnu.trove.TLinkable;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Implementation of TCObject interface.
 * <p>
 */
public abstract class TCObjectImpl implements TCObject {
  private static final TCLogger logger                      = TCLogging.getLogger(TCObjectImpl.class);

  private static final int      ACCESSED_OFFSET             = 1 << 0;
  private static final int      IS_NEW_OFFSET               = 1 << 1;
  private static final int      AUTOLOCKS_DISABLED_OFFSET   = 1 << 2;
  private static final int      EVICTION_IN_PROGRESS_OFFSET = 1 << 3;

  // XXX::This initial negative version number is important since GID is assigned in the server from 0.
  private long                  version                     = -1;

  private final ObjectID        objectID;
  protected final TCClass       tcClazz;
  private WeakReference         peerObject;
  private TLinkable             next;
  private TLinkable             previous;
  private byte                  flags                       = 0;
  private static final TCLogger consoleLogger               = CustomerLogging.getConsoleLogger();

  protected TCObjectImpl(ObjectID id, Object peer, TCClass clazz, boolean isNew) {
    this.objectID = id;
    this.tcClazz = clazz;
    if (peer != null) {
      setPeerObject(getObjectManager().newWeakObjectReference(id, peer));
    }

    setFlag(IS_NEW_OFFSET, isNew);
  }

  public boolean isShared() {
    return true;
  }

  public boolean isNull() {
    return peerObject == null || getPeerObject() == null;
  }

  public ObjectID getObjectID() {
    return objectID;
  }

  protected ClientObjectManager getObjectManager() {
    return tcClazz.getObjectManager();
  }

  public Object getPeerObject() {
    return peerObject == null ? null : peerObject.get();
  }

  protected void setPeerObject(WeakReference pojo) {
    this.peerObject = pojo;
    Object realPojo;
    if ((realPojo = peerObject.get()) instanceof Manageable) {
      Manageable m = (Manageable) realPojo;
      m.__tc_managed(this);
    }
  }

  public TCClass getTCClass() {
    return tcClazz;
  }

  public void dehydrate(DNAWriter writer) {
    tcClazz.dehydrate(this, writer, getPeerObject());
  }

  /**
   * Reconstitutes the object using the data in the DNA strand. XXX: We may need to signal (via a different signature or
   * args) that the hydration is intended to initialize the object from scratch or if it's a delta. We must avoid
   * creating a new instance of the peer object if the strand is just a delta.
   *
   * @throws ClassNotFoundException
   */
  public void hydrate(DNA from, boolean force) throws ClassNotFoundException {
    synchronized (getResolveLock()) {
      boolean isNewLoad = isNull();
      createPeerObjectIfNecessary(from);

      Object po = getPeerObject();
      if (po == null) return;
      try {
        tcClazz.hydrate(this, from, po, force);
        if (isNewLoad) performOnLoadActionIfNecessary(po);
      } catch (ClassNotFoundException e) {
        logger.warn("Re-throwing Exception: ", e);
        throw e;
      } catch (IOException e) {
        logger.warn("Re-throwing Exception: ", e);
        throw new DNAException(e);
      }
    }
  }

  private void performOnLoadActionIfNecessary(Object pojo) {
    TCClass tcc = getTCClass();
    if (tcc.hasOnLoadExecuteScript() || tcc.hasOnLoadMethod()) {
      String eval = tcc.hasOnLoadExecuteScript() ? tcc.getOnLoadExecuteScript() : "self." + tcc.getOnLoadMethod()
                                                                                  + "()";
      resolveAllReferences();

      final ClassLoader prevLoader = Thread.currentThread().getContextClassLoader();
      final boolean adjustTCL = TCThreadGroup.currentThreadInTCThreadGroup();

      if (adjustTCL) {
        ClassLoader newTCL = pojo.getClass().getClassLoader();
        if (newTCL == null) newTCL = ClassLoader.getSystemClassLoader();
        Thread.currentThread().setContextClassLoader(newTCL);
      }

      try {
        Interpreter i = new Interpreter();
        i.setClassLoader(tcc.getPeerClass().getClassLoader());
        i.set("self", pojo);
        i.eval("setAccessibility(true)");
        i.eval(eval);
      } catch (ParseException e) {
        // Error Parsing script. Use e.getMessage() instead of e.getErrorText() when there is a ParseException because
        // expectedTokenSequences in ParseException could be null and thus, may throw a NullPointerException when
        // calling
        // e.getErrorText().
        consoleLogger.error("Unable to parse OnLoad script: " + pojo.getClass() + " error: " + e.getMessage()
                            + " stack: " + e.getScriptStackTrace());
      } catch (EvalError e) {
        // General Error evaluating script
        Throwable cause = null;
        if (e instanceof TargetError) {
          cause = ((TargetError) e).getTarget();
        }

        String errorMsg = "OnLoad execute script failed for: " + pojo.getClass() + " error: " + e.getErrorText()
                          + " line: " + e.getErrorLineNumber() + "; " + e.getMessage();

        if (cause != null) {
          consoleLogger.error(errorMsg, cause);
        } else {
          consoleLogger.error(errorMsg);
        }
      } finally {
        if (adjustTCL) Thread.currentThread().setContextClassLoader(prevLoader);
      }
    }
  }

  private synchronized void setFlag(int offset, boolean value) {
    flags = Conversion.setFlag(flags, offset, value);
  }

  private synchronized boolean getFlag(int offset) {
    return Conversion.getFlag(flags, offset);
  }

  private void createPeerObjectIfNecessary(DNA from) {
    if (isNull()) {
      // TODO: set created and modified version id
      setPeerObject(getObjectManager().createNewPeer(tcClazz, from));
    }
  }

  public ObjectID setReference(String fieldName, ObjectID id) {
    throw new AssertionError("shouldn't be called");
  }

  public void setArrayReference(int index, ObjectID id) {
    throw new AssertionError("shouldn't be called");
  }

  public void setValue(String fieldName, Object obj) {
    try {
      TransparentAccess ta = (TransparentAccess) getPeerObject();
      if (ta == null) {
        // Object was GC'd so return which should lead to a re-retrieve
        return;
      }
      clearReference(fieldName);
      TCField field = getTCClass().getField(fieldName);
      if (field == null) {
        logger.warn("Data for field:" + fieldName + " was recieved but that field does not exist in class:");
        return;
      }
      if (obj instanceof ObjectID) {
        setReference(fieldName, (ObjectID) obj);
        ta.__tc_setfield(field.getName(), null);
      } else {
        // clean this up
        ta.__tc_setfield(field.getName(), obj);
      }
    } catch (Exception e) {
      // TODO: More elegant exception handling.
      throw new com.tc.object.dna.api.DNAException(e);
    }
  }

  public final int clearReferences(int toClear) {
    if (tcClazz.useResolveLockWhileClearing()) {
      synchronized (getResolveLock()) {
        return basicClearReferences(toClear);
      }
    } else {
      return basicClearReferences(toClear);
    }
  }

  private int basicClearReferences(int toClear) {
    try {
      Object po = getPeerObject();
      Assert.assertFalse(isNew()); // Shouldn't clear new Objects
      if (po == null) return 0;
      return clearReferences(po, toClear);
    } finally {
      setEvictionInProgress(false);
    }
  }

  protected abstract int clearReferences(Object pojo, int toClear);

  public final Object getResolveLock() {
    return objectID; // Save a field by using this one as the lock
  }

  public void resolveArrayReference(int index) {
    throw new AssertionError("shouldn't be called");
  }

  public ArrayIndexOutOfBoundsException checkArrayIndex(int index) {
    throw new AssertionError("shouldn't be called");
  }

  public void clearArrayReference(int index) {
    clearReference(Integer.toString(index));
  }

  public void clearReference(String fieldName) {
    // do nothing
  }

  public void resolveReference(String fieldName) {
    // do nothing
  }

  public void resolveAllReferences() {
    // override me
  }

  public void literalValueChanged(Object newValue, Object oldValue) {
    throw new UnsupportedOperationException();
  }

  public void setLiteralValue(Object newValue) {
    throw new UnsupportedOperationException();
  }

  public synchronized void setVersion(long version) {
    this.version = version;
  }

  public synchronized long getVersion() {
    return this.version;
  }

  @Override
  public String toString() {
    return getClass().getName() + "@" + System.identityHashCode(this) + "[objectID=" + objectID + ", TCClass="
           + tcClazz + "]";
  }

  public void objectFieldChanged(String classname, String fieldname, Object newValue, int index) {
    try {
      this.markAccessed();
      if (index == NULL_INDEX) {
        // Assert.eval(fieldname.indexOf('.') >= 0);
        clearReference(fieldname);
      } else {
        clearArrayReference(index);
      }
      getObjectManager().getTransactionManager().fieldChanged(this, classname, fieldname, newValue, index);
    } catch (Throwable t) {
      Util.printLogAndRethrowError(t, logger);
    }
  }

  public void objectFieldChangedByOffset(String classname, long fieldOffset, Object newValue, int index) {
    String fieldname = tcClazz.getFieldNameByOffset(fieldOffset);
    objectFieldChanged(classname, fieldname, newValue, index);
  }

  public boolean isFieldPortableByOffset(long fieldOffset) {
    return tcClazz.isPortableField(fieldOffset);
  }

  public String getFieldNameByOffset(long fieldOffset) {
    return tcClazz.getFieldNameByOffset(fieldOffset);
  }

  public void booleanFieldChanged(String classname, String fieldname, boolean newValue, int index) {
    objectFieldChanged(classname, fieldname, new Boolean(newValue), index);
  }

  public void byteFieldChanged(String classname, String fieldname, byte newValue, int index) {
    objectFieldChanged(classname, fieldname, new Byte(newValue), index);
  }

  public void charFieldChanged(String classname, String fieldname, char newValue, int index) {
    objectFieldChanged(classname, fieldname, new Character(newValue), index);
  }

  public void doubleFieldChanged(String classname, String fieldname, double newValue, int index) {
    objectFieldChanged(classname, fieldname, new Double(newValue), index);
  }

  public void floatFieldChanged(String classname, String fieldname, float newValue, int index) {
    objectFieldChanged(classname, fieldname, new Float(newValue), index);
  }

  public void intFieldChanged(String classname, String fieldname, int newValue, int index) {
    objectFieldChanged(classname, fieldname, new Integer(newValue), index);
  }

  public void longFieldChanged(String classname, String fieldname, long newValue, int index) {
    objectFieldChanged(classname, fieldname, new Long(newValue), index);
  }

  public void shortFieldChanged(String classname, String fieldname, short newValue, int index) {
    objectFieldChanged(classname, fieldname, new Short(newValue), index);
  }

  public void objectArrayChanged(int startPos, Object[] array, int length) {
    this.markAccessed();
    for (int i = 0; i < length; i++) {
      clearArrayReference(startPos + i);
    }
    getObjectManager().getTransactionManager().arrayChanged(this, startPos, array, length);
  }

  public void primitiveArrayChanged(int startPos, Object array, int length) {
    this.markAccessed();
    getObjectManager().getTransactionManager().arrayChanged(this, startPos, array, length);
  }

  public void setNext(TLinkable link) {
    this.next = link;
  }

  public void setPrevious(TLinkable link) {
    this.previous = link;
  }

  public TLinkable getNext() {
    return this.next;
  }

  public TLinkable getPrevious() {
    return this.previous;
  }

  public void markAccessed() {
    setFlag(ACCESSED_OFFSET, true);
  }

  public void clearAccessed() {
    setFlag(ACCESSED_OFFSET, false);
  }

  public boolean recentlyAccessed() {
    return getFlag(ACCESSED_OFFSET);
  }

  public int accessCount(int factor) {
    // TODO:: Implement when needed
    throw new UnsupportedOperationException();
  }

  public boolean isNew() {
    return getFlag(IS_NEW_OFFSET);
  }

  public void setNotNew() {
    // Flipping the "new" flag must occur AFTER dehydrate -- otherwise the client
    // memory manager might start nulling field values! (see canEvict() dependency on isNew() condition)

    Assert.eval(isNew());
    flags = Conversion.setFlag(flags, IS_NEW_OFFSET, false);
  }

  // These autlocking disable methods are checked in ManagerImpl. The one known use case
  // is the Hashtable used to hold sessions. We need local synchronization,
  // but we don't ever want autolocks for that particular instance
  public void disableAutoLocking() {
    setFlag(AUTOLOCKS_DISABLED_OFFSET, true);
  }

  public boolean autoLockingDisabled() {
    return getFlag(AUTOLOCKS_DISABLED_OFFSET);
  }

  private void setEvictionInProgress(boolean value) {
    setFlag(EVICTION_IN_PROGRESS_OFFSET, value);
  }

  private boolean isEvictionInProgress() {
    return getFlag(EVICTION_IN_PROGRESS_OFFSET);
  }

  public synchronized boolean canEvict() {
    boolean canEvict = isEvictable() && !(isNew() || isEvictionInProgress());
    if (canEvict) {
      setEvictionInProgress(true);
    }
    return canEvict;
  }

  protected abstract boolean isEvictable();

  public ToggleableStrongReference getOrCreateToggleRef() {
    Object peer = getPeerObject();
    if (peer == null) { throw new AssertionError("cannot create a toggle reference if peer object is gone"); }

    return getObjectManager().getOrCreateToggleRef(objectID, peer);
  }

}
