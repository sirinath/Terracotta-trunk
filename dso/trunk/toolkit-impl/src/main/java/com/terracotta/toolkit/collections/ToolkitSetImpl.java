/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.collections;

import org.terracotta.toolkit.collections.ToolkitSet;
import org.terracotta.toolkit.concurrent.locks.ToolkitReadWriteLock;
import org.terracotta.toolkit.object.Destroyable;
import org.terracotta.toolkit.object.ToolkitLockedObject;
import org.terracotta.toolkit.object.ToolkitObject;

import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.object.AbstractDestroyableToolkitObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

public class ToolkitSetImpl<E> extends AbstractDestroyableToolkitObject<ToolkitSet<E>> implements ToolkitSet<E> {
  static final Integer          DUMMY_VALUE = 0;
  private final Map<E, Integer> toolkitMap;

  public ToolkitSetImpl(ToolkitObjectFactory factory, Map<E, Integer> toolkitMap) {
    super(factory);
    this.toolkitMap = toolkitMap;
  }

  @Override
  public boolean add(E element) {
    return toolkitMap.put(element, DUMMY_VALUE) == null;
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    ReadWriteLock lock = getReadWriteLock();

    lock.writeLock().lock();
    int size = toolkitMap.size();
    try {
      Map<E, Integer> m = new HashMap<E, Integer>();
      for (E e : c) {
        m.put(e, DUMMY_VALUE);
      }
      toolkitMap.putAll(m);
      return size < toolkitMap.size();
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void clear() {
    toolkitMap.clear();
  }

  @Override
  public boolean contains(Object element) {
    return toolkitMap.containsKey(element);
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    return toolkitMap.keySet().containsAll(c);
  }

  @Override
  public boolean isEmpty() {
    return toolkitMap.isEmpty();
  }

  @Override
  public Iterator<E> iterator() {
    return toolkitMap.keySet().iterator();
  }

  @Override
  public boolean remove(Object element) {
    return toolkitMap.remove(element) != null;
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    ReadWriteLock lock = getReadWriteLock();
    lock.writeLock().lock();

    try {
      int size = toolkitMap.size();

      for (Object o : c) {
        remove(o);
      }

      return size > toolkitMap.size();
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    ReadWriteLock lock = getReadWriteLock();
    lock.writeLock().lock();

    try {
      int size = toolkitMap.size();

      for (Iterator iter = iterator(); iter.hasNext();) {
        if (!c.contains(iter.next())) {
          iter.remove();
        }
      }

      return size > toolkitMap.size();
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public int size() {
    return toolkitMap.size();
  }

  @Override
  public Object[] toArray() {
    return toolkitMap.keySet().toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return toolkitMap.keySet().toArray(a);
  }

  @Override
  public ToolkitReadWriteLock getReadWriteLock() {
    return ((ToolkitLockedObject) toolkitMap).getReadWriteLock();
  }

  @Override
  public String getName() {
    return ((ToolkitObject) toolkitMap).getName();
  }

  @Override
  public void doDestroy() {
    ((Destroyable) toolkitMap).destroy();
  }

  @Override
  public void applyDestroy() {
    throw new UnsupportedOperationException();
  }

}
