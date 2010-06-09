/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.managedobject;

import com.tc.io.TCByteBufferOutputStream;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.ObjectID;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAEncoding;
import com.tc.object.dna.api.DNAException;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.dna.api.DNA.DNAType;
import com.tc.object.dna.impl.ObjectDNAWriterImpl;
import com.tc.object.dna.impl.ObjectStringSerializer;
import com.tc.object.dna.impl.StorageDNAEncodingImpl;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.api.ObjectInstanceMonitor;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.objectserver.impl.ManagedObjectReference;
import com.tc.objectserver.managedobject.bytecode.ClassNotCompatableException;
import com.tc.objectserver.mgmt.ManagedObjectFacade;
import com.tc.text.PrettyPrintable;
import com.tc.text.PrettyPrinter;
import com.tc.text.PrettyPrinterImpl;
import com.tc.util.Assert;
import com.tc.util.Conversion;

import gnu.trove.TLinkable;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.Set;

/**
 * Responsible for maintaining the state of a shared object. Used for broadcasting new instances of an object as well as
 * having changes applied to it and keeping track of references for garbage collection. If you add fields to this object
 * that need to be serialized make sure you add them to the ManagedObjectSerializer
 */
public class ManagedObjectImpl implements ManagedObject, ManagedObjectReference, Serializable, PrettyPrintable {
  private static final TCLogger    logger                   = TCLogging.getLogger(ManagedObjectImpl.class);
  private static final DNAEncoding DNA_STORAGE_ENCODING     = new StorageDNAEncodingImpl();

  private final static byte        IS_NEW_OFFSET            = 1;
  private final static byte        IS_DIRTY_OFFSET          = 2;
  private final static byte        REFERENCED_OFFSET        = 4;
  private final static byte        REMOVE_ON_RELEASE_OFFSET = 8;
  private final static byte        PINNED_OFFSET            = 16;

  private final static byte        INITIAL_FLAG_VALUE       = IS_DIRTY_OFFSET | IS_NEW_OFFSET;

  private static final long        UNINITIALIZED_VERSION    = -1;

  final ObjectID                   id;

  long                             version                  = UNINITIALIZED_VERSION;
  transient ManagedObjectState     state;

  // TODO::Split this flag into two so that concurrency is maintained
  private volatile transient byte  flags                    = INITIAL_FLAG_VALUE;

  // TODO:: Remove Cacheable interface from this Object and remove these two references
  private transient TLinkable      previous;
  private transient TLinkable      next;

  private transient int            accessed;

  public ManagedObjectImpl(final ObjectID id) {
    Assert.assertNotNull(id);
    this.id = id;
  }

  /**
   * This is here for testing, not production use.
   */
  public boolean isEqual(final ManagedObject moi) {
    if (this == moi) { return true; }
    if (moi instanceof ManagedObjectImpl) {
      final ManagedObjectImpl mo = (ManagedObjectImpl) moi;
      boolean rv = true;
      rv &= this.id.equals(mo.id);
      rv &= this.version == mo.version;
      rv &= this.state.equals(mo.state);
      return rv;
    } else {
      return false;
    }
  }

  private void setBasicIsNew(final boolean b) {
    setFlag(IS_NEW_OFFSET, b);
  }

  private void setBasicIsDirty(final boolean b) {
    setFlag(IS_DIRTY_OFFSET, b);
  }

  private synchronized void setFlag(final int offset, final boolean value) {
    this.flags = Conversion.setFlag(this.flags, offset, value);
  }

  private synchronized boolean getFlag(final int offset) {
    return (this.flags & offset) == offset;
  }

  private boolean basicIsNew() {
    return getFlag(IS_NEW_OFFSET);
  }

  private boolean basicIsDirty() {
    return getFlag(IS_DIRTY_OFFSET);
  }

  public boolean isNew() {
    return basicIsNew();
  }

  public void setIsNew(final boolean isNew) {
    setBasicIsNew(isNew);
  }

  public boolean isDirty() {
    return basicIsDirty();
  }

  public void setIsDirty(final boolean isDirty) {
    setBasicIsDirty(isDirty);
  }

  public ObjectID getID() {
    return this.id;
  }

  public Set getObjectReferences() {
    return this.state.getObjectReferences();
  }

  public void addObjectReferencesTo(final ManagedObjectTraverser traverser) {
    this.state.addObjectReferencesTo(traverser);
  }

  public void apply(final DNA dna, final TransactionID txnID, final BackReferences includeIDs,
                    final ObjectInstanceMonitor instanceMonitor, final boolean ignoreIfOlderDNA) {
    final boolean isInitialized = isInitialized();

    final long dna_version = dna.getVersion();
    if (dna_version <= this.version) {
      if (ignoreIfOlderDNA) {
        logger.info("Ignoring apply of an old DNA for " + getClassname() + " id = " + this.id + " current version = "
                    + this.version + " dna_version = " + dna_version);
        return;
      } else {
        throw new AssertionError("Recd a DNA with version less than or equal to the current version : " + this.version
                                 + " dna_version : " + dna_version);
      }
    }
    if (dna.isDelta() && isInitialized) {
      throw new AssertionError("Uninitalized Object is applied with a delta DNA ! ManagedObjectImpl = " + toString()
                               + " DNA = " + dna + " TransactionID = " + txnID);
    } else if (!dna.isDelta() && !isInitialized) {
      // New DNA applied on old object - a No No for logical objects.
      throw new AssertionError("Old Object is applied with a non-delta DNA ! ManagedObjectImpl = " + toString()
                               + " DNA = " + dna + " TransactionID = " + txnID);
    }
    if (isInitialized) {
      instanceMonitor.instanceCreated(dna.getTypeName());
    }
    this.version = dna_version;
    final DNACursor cursor = dna.getCursor();

    if (this.state == null) {
      this.state = getStateFactory().createState(this.id, dna.getParentObjectID(), dna.getTypeName(),
                                                 dna.getDefiningLoaderDescription(), cursor);
    }
    try {
      try {
        this.state.apply(this.id, cursor, includeIDs);
      } catch (final ClassNotCompatableException cnce) {
        // reinitialize state object and try again
        reinitializeState(dna.getParentObjectID(), getClassname(), getLoaderDescription(), cursor, this.state);
        this.state.apply(this.id, cursor, includeIDs);
      }
    } catch (final IOException e) {
      throw new DNAException(e);
    }
    setIsDirty(true);
    // Not unsetting isNew() flag on apply, but rather on release
    // setBasicIsNew(false);
  }

  private boolean isInitialized() {
    return this.version == UNINITIALIZED_VERSION;
  }

  private void reinitializeState(final ObjectID pid, final String className, final String loaderDesc,
                                 final DNACursor cursor, final ManagedObjectState oldState) {
    this.state = getStateFactory().recreateState(this.id, pid, className, loaderDesc, cursor, oldState);
  }

  private ManagedObjectStateFactory getStateFactory() {
    return ManagedObjectStateFactory.getInstance();
  }

  private void writeObject(final ObjectOutputStream out) throws IOException {
    if (this.state == null) { throw new AssertionError("Null state:" + this); }
    out.defaultWriteObject();
    out.writeByte(this.state.getType());
    this.state.writeTo(out);
  }

  private void readObject(final ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    final byte type = in.readByte();
    this.state = getStateFactory().readManagedObjectStateFrom(in, type);
    setBasicIsNew(false);
    setBasicIsDirty(false);
  }

  public ManagedObjectState getManagedObjectState() {
    return this.state;
  }

  /**
   * Writes the data in the object to the DNA strand supplied.
   */
  public void toDNA(final TCByteBufferOutputStream out, final ObjectStringSerializer serializer, final DNAType type) {
    final DNAWriter writer = new ObjectDNAWriterImpl(out, this.id, getClassname(), serializer, DNA_STORAGE_ENCODING,
                                                     getLoaderDescription(), this.version, false);
    this.state.dehydrate(this.id, writer, type);
    writer.markSectionEnd();
    writer.finalizeHeader();
  }

  @Override
  public String toString() {
    // XXX: Um... this is gross.
    final StringWriter writer = new StringWriter();
    final PrintWriter pWriter = new PrintWriter(writer);
    new PrettyPrinterImpl(pWriter).visit(this);
    return writer.getBuffer().toString();
  }

  public PrettyPrinter prettyPrint(PrettyPrinter out) {
    final PrettyPrinter rv = out;
    out = out.print("ManagedObjectImpl").duplicateAndIndent().println();
    out.indent().print("identityHashCode: " + System.identityHashCode(this)).println();
    out.indent().print("id: " + this.id).println();
    out.indent().print("className: " + getClassname()).println();
    out.indent().print("version:" + this.version).println();
    out.indent().print("state: ").visit(this.state).println();
    out.indent().print("isDirty:" + basicIsDirty());
    out.indent().print("isNew:" + basicIsNew());
    out.indent().print("isReferenced:" + isReferenced()).println();
    out.indent().print("next: " + (getNext() != null) + " prev: " + (getPrevious() != null));
    return rv;
  }

  public ManagedObjectFacade createFacade(final int limit) {
    return this.state.createFacade(this.id, getClassname(), limit);
  }

  /**
   * This is public for testing
   */
  public String getClassname() {
    return this.state == null ? "UNKNOWN" : this.state.getClassName();
  }

  public String getLoaderDescription() {
    return this.state == null ? "UNKNOWN" : this.state.getLoaderDescription();
  }

  public ManagedObjectReference getReference() {
    return this;
  }

  /*********************************************************************************************************************
   * ManagedObjectReference Interface
   */
  public ManagedObject getObject() {
    return this;
  }

  public boolean isRemoveOnRelease() {
    return getFlag(REMOVE_ON_RELEASE_OFFSET);
  }

  public void markReference() {
    setFlag(REFERENCED_OFFSET, true);
  }

  public void unmarkReference() {
    setFlag(REFERENCED_OFFSET, false);
  }

  public boolean isReferenced() {
    return getFlag(REFERENCED_OFFSET);
  }

  /**
   * Pins this reference in the cache.
   */
  public void pin() {
    setFlag(PINNED_OFFSET, true);
  }

  public void unpin() {
    setFlag(PINNED_OFFSET, false);
  }

  /**
   * Determines whether or not this reference is pinned in the ObjectManager's cache. This allows the object manager to
   * lookup multiple objects one at a time without evicting them from the cache.
   */
  public boolean isPinned() {
    return getFlag(PINNED_OFFSET);
  }

  /*********************************************************************************************************************
   * ManagedObjectReference::Cacheable interface XXX:: Most of these methods are not synchronized (except when accessing
   * the flag field which can be accessed from multiple threads) 'coz it is expected that these are called under the
   * scope of a bigger sync block (from evictionPolicy)
   */

  public void setRemoveOnRelease(final boolean removeOnRelease) {
    setFlag(REMOVE_ON_RELEASE_OFFSET, removeOnRelease);
  }

  public void markAccessed() {
    this.accessed++;
  }

  public void clearAccessed() {
    this.accessed = 0;
  }

  public boolean recentlyAccessed() {
    return this.accessed > 0;
  }

  public int accessCount(final int factor) {
    this.accessed = this.accessed / factor;
    return this.accessed;
  }

  public ObjectID getObjectID() {
    return getID();
  }

  public TLinkable getNext() {
    return this.next;
  }

  public TLinkable getPrevious() {
    return this.previous;
  }

  public void setNext(final TLinkable next) {
    this.next = next;
  }

  public void setPrevious(final TLinkable previous) {
    this.previous = previous;
  }

  public synchronized boolean canEvict() {
    return !(isPinned() || isReferenced() || isNew());
  }

  public long getVersion() {
    return this.version;
  }

}
