/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.roots.impl;

import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.internal.ToolkitInternal;
import org.terracotta.toolkit.internal.concurrent.locks.ToolkitLockTypeInternal;
import org.terracotta.toolkit.object.ToolkitObject;

import com.tc.object.bytecode.PlatformService;
import com.terracotta.toolkit.concurrent.locks.ToolkitLockingApi;
import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.object.AbstractDestroyableToolkitObject;
import com.terracotta.toolkit.object.TCToolkitObject;
import com.terracotta.toolkit.object.ToolkitObjectType;
import com.terracotta.toolkit.roots.AggregateToolkitTypeRoot;
import com.terracotta.toolkit.roots.ToolkitTypeRoot;
import com.terracotta.toolkit.type.IsolatedToolkitTypeFactory;
import com.terracotta.toolkit.util.collections.WeakValueMap;

public class AggregateIsolatedToolkitTypeRoot<T extends ToolkitObject, S extends TCToolkitObject> implements
    AggregateToolkitTypeRoot<T, S> {

  private final ToolkitTypeRoot<S>[]             roots;
  private final IsolatedToolkitTypeFactory<T, S> isolatedTypeFactory;
  private final WeakValueMap<T>                  isolatedTypes;
  private final PlatformService                  platformService;

  protected AggregateIsolatedToolkitTypeRoot(ToolkitTypeRoot<S>[] roots,
                                             IsolatedToolkitTypeFactory<T, S> isolatedTypeFactory,
                                             WeakValueMap weakValueMap, PlatformService platformService) {
    this.roots = roots;
    this.isolatedTypeFactory = isolatedTypeFactory;
    this.isolatedTypes = weakValueMap;
    this.platformService = platformService;
  }

  @Override
  public T getOrCreateToolkitType(ToolkitInternal toolkit, ToolkitObjectFactory factory, String name,
                                  Configuration configuration) {
    if (name == null) { throw new NullPointerException("'name' cannot be null"); }

    ToolkitObjectType type = factory.getManufacturedToolkitObjectType();
    lock(type, name);
    try {
      T isolatedType = isolatedTypes.get(name);
      if (isolatedType != null) {
        return isolatedType;
      } else {
        S clusteredObject = getToolkitTypeRoot(name).getClusteredObject(name);
        if (clusteredObject == null) {
          clusteredObject = isolatedTypeFactory.createTCClusteredObject(configuration);
          getToolkitTypeRoot(name).addClusteredObject(name, clusteredObject);
        }
        isolatedType = isolatedTypeFactory.createIsolatedToolkitType(factory, name, configuration, clusteredObject);
        isolatedTypes.put(name, isolatedType);
        return isolatedType;
      }

    } finally {
      unlock(type, name);
      platformService.waitForAllCurrentTransactionsToComplete();
    }
  }

  @Override
  public void removeToolkitType(ToolkitObjectType toolkitObjectType, String name) {
    lock(toolkitObjectType, name);
    try {
      isolatedTypes.remove(name);
      getToolkitTypeRoot(name).removeClusteredObject(name);
    } finally {
      unlock(toolkitObjectType, name);
    }
  }

  private ToolkitTypeRoot<S> getToolkitTypeRoot(String name) {
    return roots[Math.abs(name.hashCode() % roots.length)];
  }

  private void lock(ToolkitObjectType toolkitObjectType, String name) {
    ToolkitLockingApi.lock(toolkitObjectType, name, ToolkitLockTypeInternal.WRITE, platformService);
  }

  private void unlock(ToolkitObjectType toolkitObjectType, String name) {
    ToolkitLockingApi.unlock(toolkitObjectType, name, ToolkitLockTypeInternal.WRITE, platformService);
  }

  @Override
  public void applyDestroy(String name) {
    this.isolatedTypes.remove(name);
  }

  @Override
  public final void destroy(AbstractDestroyableToolkitObject obj, ToolkitObjectType type) {
    lock(type, obj.getName());
    try {
      if (!obj.isDestroyed()) {
        removeToolkitType(type, obj.getName());
        obj.destroyFromCluster();
      }
    } finally {
      unlock(type, obj.getName());
    }
  }
}
