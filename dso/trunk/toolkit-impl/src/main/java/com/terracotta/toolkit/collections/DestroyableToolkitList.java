/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.collections;

import org.terracotta.toolkit.collections.ToolkitList;
import org.terracotta.toolkit.concurrent.locks.ToolkitReadWriteLock;

import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.object.AbstractDestroyableToolkitObject;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public class DestroyableToolkitList<E> extends AbstractDestroyableToolkitObject<ToolkitList> implements ToolkitList<E> {

  private volatile ToolkitList<E> list;
  private final String            name;

  public DestroyableToolkitList(ToolkitObjectFactory factory, ToolkitListImpl<E> list, String name) {
    super(factory);
    this.list = list;
    this.name = name;
    list.setApplyDestroyCallback(getDestroyApplicator());
  }

  @Override
  public void afterDestroy() {
    this.list = DestroyedInstanceProxy.createNewInstance(ToolkitList.class, getName());
  }

  @Override
  public void doDestroy() {
    list.destroy();
  }

  @Override
  public int size() {
    return list.size();
  }

  @Override
  public boolean isEmpty() {
    return list.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    return list.contains(o);
  }

  @Override
  public Iterator<E> iterator() {
    return new DestroyableIterator(list.iterator(), this);
  }

  @Override
  public Object[] toArray() {
    return list.toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return list.toArray(a);
  }

  @Override
  public boolean add(E e) {
    return list.add(e);
  }

  @Override
  public boolean remove(Object o) {
    return list.remove(o);
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    return list.containsAll(c);
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    return list.addAll(c);
  }

  @Override
  public boolean addAll(int index, Collection<? extends E> c) {
    return list.addAll(index, c);
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    return list.removeAll(c);
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    return list.retainAll(c);
  }

  @Override
  public void clear() {
    list.clear();
  }

  @Override
  public E get(int index) {
    return list.get(index);
  }

  @Override
  public E set(int index, E element) {
    return list.set(index, element);
  }

  @Override
  public void add(int index, E element) {
    list.add(index, element);
  }

  @Override
  public E remove(int index) {
    return list.remove(index);
  }

  @Override
  public int indexOf(Object o) {
    return list.indexOf(o);
  }

  @Override
  public int lastIndexOf(Object o) {
    return list.lastIndexOf(o);
  }

  @Override
  public ListIterator<E> listIterator() {
    return list.listIterator();
  }

  @Override
  public ListIterator<E> listIterator(int index) {
    return list.listIterator(index);
  }

  @Override
  public List<E> subList(int fromIndex, int toIndex) {
    return new SubListWrapper(list.subList(fromIndex, toIndex));
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public ToolkitReadWriteLock getReadWriteLock() {
    return list.getReadWriteLock();
  }

  private class SubListWrapper implements List<E> {
    private final List<E> subList;

    public SubListWrapper(List<E> subList) {
      this.subList = subList;
    }

    public int size() {
      checkDestroyed();
      return subList.size();
    }

    public boolean isEmpty() {
      checkDestroyed();
      return subList.isEmpty();
    }

    public boolean contains(Object o) {
      checkDestroyed();
      return subList.contains(o);
    }

    public Iterator<E> iterator() {
      checkDestroyed();
      return new DestroyableIterator(subList.iterator(), DestroyableToolkitList.this);
    }

    public Object[] toArray() {
      checkDestroyed();
      return subList.toArray();
    }

    public <T> T[] toArray(T[] a) {
      checkDestroyed();
      return subList.toArray(a);
    }

    public boolean add(E e) {
      checkDestroyed();
      return subList.add(e);
    }

    public boolean remove(Object o) {
      checkDestroyed();
      return subList.remove(o);
    }

    public boolean containsAll(Collection<?> c) {
      checkDestroyed();
      return subList.containsAll(c);
    }

    public boolean addAll(Collection<? extends E> c) {
      checkDestroyed();
      return subList.addAll(c);
    }

    public boolean addAll(int index, Collection<? extends E> c) {
      checkDestroyed();
      return subList.addAll(index, c);
    }

    public boolean removeAll(Collection<?> c) {
      checkDestroyed();
      return subList.removeAll(c);
    }

    public boolean retainAll(Collection<?> c) {
      checkDestroyed();
      return subList.retainAll(c);
    }

    public void clear() {
      checkDestroyed();
      subList.clear();
    }

    @Override
    public boolean equals(Object o) {
      checkDestroyed();
      return subList.equals(o);
    }

    @Override
    public int hashCode() {
      checkDestroyed();
      return subList.hashCode();
    }

    public E get(int index) {
      checkDestroyed();
      return subList.get(index);
    }

    public E set(int index, E element) {
      checkDestroyed();
      return subList.set(index, element);
    }

    public void add(int index, E element) {
      checkDestroyed();
      subList.add(index, element);
    }

    public E remove(int index) {
      checkDestroyed();
      return subList.remove(index);
    }

    public int indexOf(Object o) {
      checkDestroyed();
      return subList.indexOf(o);
    }

    public int lastIndexOf(Object o) {
      checkDestroyed();
      return subList.lastIndexOf(o);
    }

    public ListIterator<E> listIterator() {
      checkDestroyed();
      return subList.listIterator();
    }

    public ListIterator<E> listIterator(int index) {
      checkDestroyed();
      return subList.listIterator(index);
    }

    public List<E> subList(int fromIndex, int toIndex) {
      checkDestroyed();
      return new SubListWrapper(subList.subList(fromIndex, toIndex));
    }

    private void checkDestroyed() {
      if (isDestroyed()) { throw new IllegalStateException("The List backing this subList is already destroyed."); }
    }

  }

}
