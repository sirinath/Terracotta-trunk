/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.util.concurrent;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

/**
 * This class provides a thread safe map interface (by extending Hashtable) and adds a way to easily and synchronously
 * iterator over the list of values as an array. This map is very useful when you want a snap shot of the values to
 * iterator over and dont want to hold up access to the map the whole time while you are iteratoring over the list to
 * avoid concurrent modification exception.
 * <p>
 * For example : <code>
 *  Hashtable t = ....
 *  for(Iterator i = t.values().iterator(); i.hashNext(); ) {
 *    // do something 
 *  }
 *  </code>
 * In the above code, if multiple threads are accessing t, to avoid ConcurrentModificationException, you need to
 * synchronize the entire for loop.
 * <p>
 * Using CopyOnWriteArrayMap and using the values() method will give you a snapshot of the values thus avoid
 * synchronizing the map for the entire duration of the for loop.
 * <p>
 * This is achieved by maintaining an internal copy of the values in an array and copying that on modification. So an in
 * any CopyOnWrite class this is only effective on small datasets with lots of reads and few writes.
 */
public class CopyOnWriteArrayMap extends Hashtable {

  public interface TypedArrayFactory {
    public Object[] createTypedArray(int size);
  }

  private static final TypedArrayFactory OBJECT_ARRAY_FACTORY = new TypedArrayFactory() {
                                                                public Object[] createTypedArray(int size) {
                                                                  return new Object[size];
                                                                }
                                                              };

  private volatile Object                _values[];
  private final TypedArrayFactory        _factory;

  public CopyOnWriteArrayMap() {
    this(OBJECT_ARRAY_FACTORY);
  }

  public CopyOnWriteArrayMap(int initialCapacity, float loadFactor) {
    this(initialCapacity, loadFactor, OBJECT_ARRAY_FACTORY);
  }

  public CopyOnWriteArrayMap(int initialCapacity) {
    this(initialCapacity, OBJECT_ARRAY_FACTORY);
  }

  public CopyOnWriteArrayMap(TypedArrayFactory factory) {
    _factory = factory;
    _values = _factory.createTypedArray(0);
  }

  public CopyOnWriteArrayMap(int initialCapacity, float loadFactor, TypedArrayFactory factory) {
    super(initialCapacity, loadFactor);
    _factory = factory;
    _values = _factory.createTypedArray(0);
  }

  public CopyOnWriteArrayMap(int initialCapacity, TypedArrayFactory factory) {
    super(initialCapacity);
    _factory = factory;
    _values = _factory.createTypedArray(0);
  }

  public synchronized void clear() {
    super.clear();
    _values = _factory.createTypedArray(0);
  }

  /**
   * This returns a Read only set since remove is not implemented on the set due to lack to time.
   */
  public Set entrySet() {
    return new ReadOnlyEntrySet(super.entrySet());
  }

  /**
   * This returns a Read only set since remove is not implemented on the set due to lack to time.
   */
  public Set keySet() {
    return new ReadOnlySet(super.entrySet());
  }

  public synchronized Object put(Object key, Object value) {
    Object old = super.put(key, value);
    if (old == null) {
      Object[] old_values = _values;
      _values = _factory.createTypedArray(old_values.length + 1);
      System.arraycopy(old_values, 0, _values, 0, old_values.length);
      _values[old_values.length] = value;
    } else {
      Object[] old_values = _values;
      int length = old_values.length;
      // XXX:: doing an explicit copy so that the previous snapshots are not messed upon.
      _values = _factory.createTypedArray(length);
      for (int i = 0; i < length; i++) {
        _values[i] = (old == old_values[i] ? value : old_values[i]);
      }
    }
    return old;
  }

  public synchronized void putAll(Map t) {
    // calls into put anyways
    super.putAll(t);
  }

  public synchronized Object remove(Object key) {
    Object old = super.remove(key);
    if (old != null) {
      Object[] old_values = _values;
      int length = old_values.length;
      _values = _factory.createTypedArray(length - 1);
      int i = 0;
      boolean found = false;
      for (int j = 0; j < length; j++) {
        if (found || old != old_values[j]) {
          _values[i++] = old_values[j];
        } else {
          found = true;
        }
      }
    }
    return old;
  }

  public synchronized Collection values() {
    return Arrays.asList(_values);
  }

  public synchronized Object[] valuesToArray() {
    return _values;
  }

  public static class ReadOnlyEntrySet extends ReadOnlySet {

    public ReadOnlyEntrySet(Set set) {
      super(set);
    }

    public Iterator iterator() {
      return new ReadOnlyEntrySetIterator(set.iterator());
    }
  }

  public static class ReadOnlySet extends AbstractSet {

    protected final Set set;

    public ReadOnlySet(Set set) {
      this.set = set;
    }

    public boolean add(Object o) {
      throw new UnsupportedOperationException();
    }

    public boolean addAll(Collection c) {
      throw new UnsupportedOperationException();
    }

    public void clear() {
      throw new UnsupportedOperationException();
    }

    public boolean contains(Object o) {
      return set.contains(o);
    }

    public boolean containsAll(Collection c) {
      return set.containsAll(c);
    }

    public boolean isEmpty() {
      return set.isEmpty();
    }

    public Iterator iterator() {
      return new ReadOnlyIterator(set.iterator());
    }

    public boolean remove(Object o) {
      throw new UnsupportedOperationException();
    }

    public boolean removeAll(Collection c) {
      throw new UnsupportedOperationException();
    }

    public boolean retainAll(Collection c) {
      throw new UnsupportedOperationException();
    }

    public int size() {
      return set.size();
    }

  }

  public static class ReadOnlyEntrySetIterator extends ReadOnlyIterator {

    public ReadOnlyEntrySetIterator(Iterator iterator) {
      super(iterator);
    }

    public Object next() {
      return new ReadOnlyEntry((Map.Entry) iterator.next());
    }
  }

  public static class ReadOnlyIterator implements Iterator {

    protected final Iterator iterator;

    public ReadOnlyIterator(Iterator iterator) {
      this.iterator = iterator;
    }

    public boolean hasNext() {
      return iterator.hasNext();
    }

    public Object next() {
      return iterator.next();
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }

  }

  public static class ReadOnlyEntry implements Map.Entry {

    private final Entry entry;

    public ReadOnlyEntry(Entry entry) {
      this.entry = entry;
    }

    public Object getKey() {
      return entry.getKey();
    }

    public Object getValue() {
      return entry.getValue();
    }

    public Object setValue(Object value) {
      throw new UnsupportedOperationException();
    }
  }
}
