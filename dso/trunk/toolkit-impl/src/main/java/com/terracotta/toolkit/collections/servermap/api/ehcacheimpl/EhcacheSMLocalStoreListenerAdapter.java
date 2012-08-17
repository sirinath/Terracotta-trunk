/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.collections.servermap.api.ehcacheimpl;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.event.CacheEventListener;

import com.terracotta.toolkit.collections.servermap.api.ServerMapLocalStoreListener;

public class EhcacheSMLocalStoreListenerAdapter implements CacheEventListener {

  private final ServerMapLocalStoreListener serverMapListener;

  public EhcacheSMLocalStoreListenerAdapter(ServerMapLocalStoreListener serverMapListener) {
    this.serverMapListener = serverMapListener;
  }

  public void notifyElementEvicted(Ehcache cache, Element element) {
    serverMapListener.notifyElementEvicted(element.getObjectKey(), element.getObjectValue());
  }

  public void notifyElementExpired(Ehcache cache, Element element) {
    serverMapListener.notifyElementExpired(element.getObjectKey(), element.getObjectValue());
  }

  public void notifyElementRemoved(Ehcache cache, Element element) throws CacheException {
    // no-op
  }

  public void notifyElementPut(Ehcache cache, Element element) throws CacheException {
    // no-op
  }

  public void notifyElementUpdated(Ehcache cache, Element element) throws CacheException {
    // no-op
  }

  public void notifyRemoveAll(Ehcache cache) {
    // no-op
  }

  public void dispose() {
    // no-op
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    throw new CloneNotSupportedException();
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((serverMapListener == null) ? 0 : serverMapListener.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    EhcacheSMLocalStoreListenerAdapter other = (EhcacheSMLocalStoreListenerAdapter) obj;
    if (serverMapListener == null) {
      if (other.serverMapListener != null) return false;
    } else if (!serverMapListener.equals(other.serverMapListener)) return false;
    return true;
  }

}
