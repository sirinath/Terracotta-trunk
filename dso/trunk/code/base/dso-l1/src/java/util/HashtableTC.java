/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package java.util;

import com.tc.object.ObjectID;
import com.tc.object.TCObject;
import com.tc.object.bytecode.Clearable;
import com.tc.object.bytecode.Manageable;
import com.tc.object.bytecode.ManagerUtil;
import com.tc.object.bytecode.TCMap;
import com.tc.object.bytecode.hook.impl.Util;

import java.util.Collections.SynchronizedCollection;
import java.util.Collections.SynchronizedSet;
import java.util.Map.Entry;

/*
 * This class will be merged with java.lang.Hashtable in the bootjar. This hashtable can store ObjectIDs instead of
 * Objects to save memory and transparently fault Objects as needed. It can also clear references. For General rules
 *
 * @see HashMapTC class
 */
public class HashtableTC extends Hashtable implements TCMap, Manageable, Clearable {

  private volatile transient TCObject $__tc_MANAGED;
  private boolean                     evictionEnabled = true;

  public synchronized void clear() {
    if (__tc_isManaged()) {
      ManagerUtil.checkWriteAccess(this);
      ManagerUtil.logicalInvoke(this, "clear()V", new Object[0]);
    }
    super.clear();
  }

  public synchronized Object clone() {
    if (__tc_isManaged()) {
      Hashtable clone = new Hashtable(this);

      // This call to fixTCObjectReference isn't strictly required, but if someone every changes
      // this method to actually use any built-in clone mechanism, it will be needed -- better safe than sorry here
      return Util.fixTCObjectReferenceOfClonedObject(this, clone);
    }

    return super.clone();
  }

  // Values that contains ObjectIDs are already wrapped, so this should be fine
  public synchronized boolean contains(Object value) {
    return super.contains(value);
  }

  // XXX:: Keys can't be ObjectIDs as of Now.
  public synchronized boolean containsKey(Object key) {
    return super.containsKey(key);
  }

  public boolean containsValue(Object value) {
    return super.containsValue(value);
  }

  public synchronized boolean equals(Object o) {
    return super.equals(o);
  }

  /*
   * This method uses __tc_getEntry() instead of a get() and put() to avoid changing the modCount in shared mode
   */
  public synchronized Object get(Object key) {
    if (__tc_isManaged()) {
      Map.Entry e = __tc_getEntry(key);
      if (e == null) return null;
      Object value = e.getValue();
      Object actualValue = unwrapValueIfNecessary(value);
      if (actualValue != value) {
        e.setValue(actualValue);
      }
      return actualValue;
    } else {
      return super.get(key);
    }
  }

  public synchronized int hashCode() {
    return super.hashCode();
  }

  public synchronized boolean isEmpty() {
    return super.isEmpty();
  }

  /*
   * This method needs to call logicalInvoke before modifying the local state to avoid inconsistency when throwing
   * NonPortableExceptions TODO:: provide special method for the applicator
   */
  public synchronized Object put(Object key, Object value) {
    if (__tc_isManaged()) {
      if (key == null || value == null) { throw new NullPointerException(); }
      ManagerUtil.checkWriteAccess(this);
      Entry e = __tc_getEntry(key);
      if (e == null) {
        // New mapping
        ManagerUtil.logicalInvoke(this, "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", new Object[] {
            key, value });
        // Sucks to do a second lookup !!
        return unwrapValueIfNecessary(super.put(key, wrapValueIfNecessary(value)));
      } else {
        Object old = unwrapValueIfNecessary(e.getValue());
        if (old != value) {
          ManagerUtil.logicalInvoke(this, "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", new Object[] {
              e.getKey(), value });
          e.setValue(wrapValueIfNecessary(value));
        }
        return old;
      }
    } else {
      return super.put(key, value);
    }
  }

  /**
   * This method is only to be invoked from the applicator thread. This method does not need to check if the map is
   * managed as it will always be managed when called by the applicator thread. In addition, this method does not need
   * to be synchronized under getResolveLock() as the applicator thread is already under the scope of such
   * synchronization.
   */
  public synchronized void __tc_applicator_put(Object key, Object value) {
    if (key == null || value == null) { throw new NullPointerException(); }
    super.put(key, wrapValueIfNecessary(value));
  }

  private static Object unwrapValueIfNecessary(Object value) {
    if (value instanceof ValuesWrapper) {
      return ((ValuesWrapper) value).getValue();
    } else {
      return value;
    }
  }

  private static Object unwrapValueIfNecessaryFaultBreadth(Object value, ObjectID parentContext) {
    if (value instanceof ValuesWrapper) {
      return ((ValuesWrapper) value).getValueFaultBreadth(parentContext);
    } else {
      return value;
    }
  }

  private static Object wrapValueIfNecessary(Object value) {
    if (value instanceof ObjectID) {
      // value cant be NULL_ID as Hashtable doesnt handle null !
      return new ValuesWrapper(value);
    } else {
      return value;
    }
  }

  public synchronized void putAll(Map arg0) {
    super.putAll(arg0);
  }

  public synchronized Object remove(Object key) {
    if (__tc_isManaged()) {
      ManagerUtil.checkWriteAccess(this);

      Entry entry = __tc_removeEntryForKey(key);
      if (entry == null) { return null; }

      Object rv = unwrapValueIfNecessary(entry.getValue());

      ManagerUtil.logicalInvoke(this, "remove(Ljava/lang/Object;)Ljava/lang/Object;", new Object[] { entry.getKey() });

      return rv;
    } else {
      return super.remove(key);
    }
  }

  /**
   * This method is only to be invoked from the applicator thread. This method does not need to check if the map is
   * managed as it will always be managed when called by the applicator thread. In addition, this method does not need
   * to be synchronized under getResolveLock() as the applicator thread is already under the scope of such
   * synchronization.
   */
  public synchronized void __tc_applicator_remove(Object key) {
    super.remove(key);
  }

  public synchronized void __tc_remove_logical(Object key) {
    if (__tc_isManaged()) {
      ManagerUtil.checkWriteAccess(this);

      Entry entry = __tc_removeEntryForKey(key);
      if (entry == null) { return; }

      ManagerUtil.logicalInvoke(this, "remove(Ljava/lang/Object;)Ljava/lang/Object;", new Object[] { entry.getKey() });

    } else {
      super.remove(key);
    }
  }

  public synchronized Collection __tc_getAllEntriesSnapshot() {
    Set entrySet = super.entrySet();
    return new ArrayList(entrySet);
  }

  public synchronized Collection __tc_getAllLocalEntriesSnapshot() {
    Set entrySet = super.entrySet();
    int entrySetSize = entrySet.size();
    if (entrySetSize == 0) { return Collections.EMPTY_LIST; }

    Object[] tmp = new Object[entrySetSize];
    int index = -1;
    for (Iterator i = entrySet.iterator(); i.hasNext();) {
      Map.Entry e = (Map.Entry) i.next();
      if (!(e.getValue() instanceof ValuesWrapper)) {
        index++;
        tmp[index] = e;
      }
    }

    if (index < 0) { return Collections.EMPTY_LIST; }
    Object[] rv = new Object[index + 1];
    System.arraycopy(tmp, 0, rv, 0, index + 1);
    return Arrays.asList(rv);
  }

  public synchronized int size() {
    return super.size();
  }

  public synchronized String toString() {
    return super.toString();
  }

  public synchronized Enumeration keys() {
    return new EnumerationWrapper(super.keys());
  }

  public Set keySet() {
    Collections.SynchronizedSet ss = (SynchronizedSet) super.keySet();
    return Collections.synchronizedSet(new KeySetWrapper((Set) ss.c), ss.mutex);
  }

  public synchronized Enumeration elements() {
    return new EnumerationWrapper(super.elements());
  }

  public Set entrySet() {
    return nonOverridableEntrySet();
  }

  private Set nonOverridableEntrySet() {
    Collections.SynchronizedSet ss = (SynchronizedSet) super.entrySet();
    return Collections.synchronizedSet(new EntrySetWrapper((Set) ss.c), ss.mutex);
  }

  public Collection values() {
    Collections.SynchronizedCollection sc = (SynchronizedCollection) super.values();
    return Collections.synchronizedCollection(new ValuesCollectionWrapper(sc.c), sc.mutex);
  }

  /**
   * Clearable interface - called by CacheManager thru TCObjectLogical
   */
  public synchronized int __tc_clearReferences(int toClear) {
    if (!__tc_isManaged()) { throw new AssertionError("clearReferences() called on Unmanaged Map"); }
    int cleared = 0;
    for (Iterator i = super.entrySet().iterator(); i.hasNext() && toClear > cleared;) {
      Map.Entry e = (Map.Entry) i.next();
      if (e.getValue() instanceof Manageable) {
        Manageable m = (Manageable) e.getValue();
        TCObject tcObject = m.__tc_managed();
        if (tcObject != null && !tcObject.recentlyAccessed()) {
          e.setValue(wrapValueIfNecessary(tcObject.getObjectID()));
          cleared++;
        }
      }
    }
    return cleared;
  }

  public boolean isEvictionEnabled() {
    return evictionEnabled;
  }

  public void setEvictionEnabled(boolean enabled) {
    evictionEnabled = enabled;
  }

  public void __tc_managed(TCObject tcObject) {
    $__tc_MANAGED = tcObject;
  }

  public TCObject __tc_managed() {
    return $__tc_MANAGED;
  }

  public boolean __tc_isManaged() {
    // TCObject tcManaged = $__tc_MANAGED;
    // return (tcManaged != null && (tcManaged instanceof TCObjectPhysical || tcManaged instanceof TCObjectLogical));
    return $__tc_MANAGED != null;
  }

  protected Map.Entry __tc_getEntry(Object key) {
    // This method is instrumented during bootjar creation into the vanilla (which gets tainted) java.util.Hashtable.
    // This is needed so that we can easily get access to the Original Key on put without a traversal or proxy Keys.
    throw new RuntimeException("This should never execute! Check BootJarTool");
  }

  protected Map.Entry __tc_removeEntryForKey(Object key) {
    // This method is instrumented during bootjar creation into the vanilla (which gets tainted) java.util.Hashtable.
    throw new RuntimeException("This should never execute! Check BootJarTool");
  }

  private static class ValuesWrapper {

    private Object value;

    public ValuesWrapper(Object value) {
      this.value = value;
    }

    public boolean equals(Object obj) {
      return getValue().equals(obj);
    }

    Object getValue() {
      if (value instanceof ObjectID) {
        value = ManagerUtil.lookupObject((ObjectID) value);
      }
      return value;
    }

    public Object getValueFaultBreadth(ObjectID parentContext) {
      if (value instanceof ObjectID) {
        value = ManagerUtil.lookupObjectWithParentContext((ObjectID) value, parentContext);
      }
      return value;
    }

    public int hashCode() {
      return getValue().hashCode();
    }

    public String toString() {
      return getValue().toString();
    }
  }

  private class EntryWrapper implements Map.Entry {

    private final Entry entry;

    public EntryWrapper(Entry entry) {
      this.entry = entry;
    }

    public boolean equals(Object o) {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          return entry.equals(o);
        }
      } else {
        return entry.equals(o);
      }
    }

    public Object getKey() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          return entry.getKey();
        }
      } else {
        return entry.getKey();
      }
    }

    public Object getValue() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          return unwrapValueIfNecessary(entry.getValue());
        }
      } else {
        return entry.getValue();
      }
    }

    public Object getValueFaultBreadth() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          return unwrapValueIfNecessaryFaultBreadth(entry.getValue(), __tc_managed().getObjectID());
        }
      } else {
        return entry.getValue();
      }
    }

    public int hashCode() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          return entry.hashCode();
        }
      } else {
        return entry.hashCode();
      }
    }

    public Object setValue(Object value) {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          // This check is done to solve the chicken and egg problem. Should I modify the local copy or the remote copy
          // ? (both has error checks that we want to take place before any modification is propagated
          if (value == null) throw new NullPointerException();
          ManagerUtil.checkWriteAccess(HashtableTC.this);
          ManagerUtil.logicalInvoke(HashtableTC.this, "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                    new Object[] { getKey(), value });
          return unwrapValueIfNecessary(entry.setValue(value));
        }
      } else {
        return entry.setValue(value);
      }
    }
  }

  private class EntrySetWrapper extends AbstractSet {

    private final Set entrySet;

    public EntrySetWrapper(Set entrySet) {
      this.entrySet = entrySet;
    }

    public boolean add(Object arg0) {
      return entrySet.add(arg0);
    }

    public void clear() {
      // XXX:: Calls Hashtable.clear()
      entrySet.clear();
    }

    public boolean contains(Object o) {
      return entrySet.contains(o);
    }

    public Iterator iterator() {
      return new EntriesIterator(entrySet.iterator());
    }

    public boolean remove(Object o) {

      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          ManagerUtil.checkWriteAccess(HashtableTC.this);

          if (!(o instanceof Map.Entry)) { return false; }

          Entry entryToRemove = (Entry) o;

          Entry entry = __tc_removeEntryForKey(entryToRemove.getKey());
          if (entry == null) { return false; }

          ManagerUtil.logicalInvoke(HashtableTC.this, "remove(Ljava/lang/Object;)Ljava/lang/Object;",
                                    new Object[] { entry.getKey() });
          return true;
        }
      } else {
        return entrySet.remove(o);
      }
    }

    public int size() {
      return entrySet.size();
    }
  }

  private class KeySetWrapper extends AbstractSet {

    private final Set keys;

    public KeySetWrapper(Set keys) {
      this.keys = keys;
    }

    public void clear() {
      keys.clear();
    }

    public boolean contains(Object o) {
      return keys.contains(o);
    }

    public Iterator iterator() {
      return new KeysIterator(nonOverridableEntrySet().iterator());
    }

    // XXX:: Calls Hashtable.remove();
    public boolean remove(Object o) {
      return keys.remove(o);
    }

    public int size() {
      return keys.size();
    }

  }

  private class ValuesCollectionWrapper extends AbstractCollection {

    private final Collection values;

    public ValuesCollectionWrapper(Collection values) {
      this.values = values;
    }

    // XXX:: Calls Hashtable.clear();
    public void clear() {
      values.clear();
    }

    // XXX:: Calls Hashtable.containsValue();
    public boolean contains(Object o) {
      return values.contains(o);
    }

    public Iterator iterator() {
      return new ValuesIterator(nonOverridableEntrySet().iterator());
    }

    public int size() {
      return values.size();
    }

  }

  // Hashtable Iterator doesnt synchronize access to the table !!
  private class EntriesIterator implements Iterator {

    private final Iterator entries;
    private Map.Entry      currentEntry;

    public EntriesIterator(Iterator entries) {
      this.entries = entries;
    }

    public boolean hasNext() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          return entries.hasNext();
        }
      } else {
        return entries.hasNext();
      }
    }

    public Object next() {
      currentEntry = nextEntry();
      if (currentEntry instanceof EntryWrapper) {
        // This check is here since this class is extended by ValuesIterator too.
        return currentEntry;
      } else {
        return new EntryWrapper(currentEntry);
      }
    }

    protected Map.Entry nextEntry() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          return (Map.Entry) entries.next();
        }
      } else {
        return (Map.Entry) entries.next();
      }
    }

    public void remove() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          ManagerUtil.checkWriteAccess(HashtableTC.this);
          entries.remove();
          ManagerUtil.logicalInvoke(HashtableTC.this, "remove(Ljava/lang/Object;)Ljava/lang/Object;",
                                    new Object[] { currentEntry.getKey() });
        }
      } else {
        entries.remove();
      }
    }
  }

  private class KeysIterator extends EntriesIterator {

    public KeysIterator(Iterator entries) {
      super(entries);
    }

    public Object next() {
      Map.Entry e = (Map.Entry) super.next();
      return e.getKey();
    }
  }

  private class ValuesIterator extends EntriesIterator {

    public ValuesIterator(Iterator entries) {
      super(entries);
    }

    public Object next() {
      Map.Entry e = (Map.Entry) super.next();
      if (e instanceof EntryWrapper) {
        EntryWrapper ew = (EntryWrapper) e;
        return ew.getValueFaultBreadth();
      }
      return e.getValue();
    }
  }

  private class EnumerationWrapper implements Enumeration {

    private final Enumeration enumeration;

    public EnumerationWrapper(Enumeration enumeration) {
      this.enumeration = enumeration;
    }

    public boolean hasMoreElements() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          return enumeration.hasMoreElements();
        }
      } else {
        return enumeration.hasMoreElements();
      }
    }

    public Object nextElement() {
      if (__tc_isManaged()) {
        synchronized (HashtableTC.this) {
          // XXX:: This is done for both keys and values, for keys it has no effect
          return unwrapValueIfNecessary(enumeration.nextElement());
        }
      } else {
        return enumeration.nextElement();
      }
    }
  }
}
