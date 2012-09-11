/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.roots.impl;

import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.internal.ToolkitInternal;

import com.tc.object.bytecode.ManagerUtil;
import com.tc.object.locks.LockLevel;
import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.object.AbstractDestroyableToolkitObject;
import com.terracotta.toolkit.object.TCToolkitObject;
import com.terracotta.toolkit.object.ToolkitObjectStripe;
import com.terracotta.toolkit.object.ToolkitObjectType;
import com.terracotta.toolkit.roots.AggregateToolkitTypeRoot;
import com.terracotta.toolkit.roots.ToolkitTypeRoot;
import com.terracotta.toolkit.type.DistributedToolkitType;
import com.terracotta.toolkit.type.DistributedToolkitTypeFactory;
import com.terracotta.toolkit.util.collections.WeakValueMap;

public class AggregateDistributedToolkitTypeRoot<T extends DistributedToolkitType<S>, S extends TCToolkitObject>
    implements AggregateToolkitTypeRoot<T, S> {

  private final ToolkitTypeRoot<ToolkitObjectStripe<S>>[] roots;
  private final DistributedToolkitTypeFactory<T, S>       distributedTypeFactory;
  private final WeakValueMap<T>                           localCache;

  protected AggregateDistributedToolkitTypeRoot(ToolkitTypeRoot<ToolkitObjectStripe<S>>[] roots,
                                                DistributedToolkitTypeFactory<T, S> factory, WeakValueMap weakValueMap) {
    this.roots = roots;
    this.distributedTypeFactory = factory;
    this.localCache = weakValueMap;
  }

  @Override
  public T getOrCreateToolkitType(ToolkitInternal toolkit, ToolkitObjectFactory factory, String name,
                                  Configuration configuration) {
    if (name == null) { throw new NullPointerException("'name' cannot be null"); }

    ToolkitObjectType type = factory.getManufacturedToolkitObjectType();
    lock(type, name);
    try {
      T distributedType = localCache.get(name);
      if (distributedType != null) {
        distributedTypeFactory.validateExistingLocalInstanceConfig(distributedType, configuration);
        return distributedType;
      } else {
        Configuration effectiveConfig = null;
        final ToolkitObjectStripe<S>[] stripeObjects;
        if (roots[0].getClusteredObject(name) != null) {
          stripeObjects = lookupStripeObjects(name);
          effectiveConfig = distributedTypeFactory.newConfigForCreationInLocalNode(stripeObjects, configuration);
        } else {
          // need to create stripe objects
          // make sure config is complete
          effectiveConfig = distributedTypeFactory.newConfigForCreationInCluster(configuration);
          stripeObjects = createStripeObjects(name, effectiveConfig);
        }

        distributedType = distributedTypeFactory.createDistributedType(toolkit, factory, name, stripeObjects,
                                                                       effectiveConfig);
        localCache.put(name, distributedType);
        return distributedType;
      }
    } finally {
      unlock(type, name);
      ManagerUtil.waitForAllCurrentTransactionsToComplete();
    }
  }

  private ToolkitObjectStripe<S>[] createStripeObjects(String name, Configuration configuration) throws AssertionError {
    final ToolkitObjectStripe<S>[] stripeObjects = distributedTypeFactory.createStripeObjects(name, configuration,
                                                                                              roots.length);
    if (stripeObjects == null || stripeObjects.length != roots.length) {
      //
      throw new AssertionError(
                               "DistributedTypeFactory should create as many ClusteredObjectStripe's as there are stripes - numStripes: "
                                   + roots.length + ", created: "
                                   + (stripeObjects == null ? "null" : stripeObjects.length));
    }
    for (int i = 0; i < roots.length; i++) {
      ToolkitTypeRoot<ToolkitObjectStripe<S>> root = roots[i];
      root.addClusteredObject(name, stripeObjects[i]);
    }
    return stripeObjects;
  }

  private ToolkitObjectStripe<S>[] lookupStripeObjects(String name) throws AssertionError {
    final ToolkitObjectStripe<S>[] stripeObjects = new ToolkitObjectStripe[roots.length];
    // already created.. make sure was created in all stripes
    for (int i = 0; i < roots.length; i++) {
      ToolkitTypeRoot<ToolkitObjectStripe<S>> root = roots[i];
      stripeObjects[i] = root.getClusteredObject(name);
      if (stripeObjects[i] == null) {
        //
        throw new AssertionError("ClusteredObjectStripe not created in all stripes - missing in stripe: " + i);
      }
    }
    return stripeObjects;
  }

  @Override
  public void removeToolkitType(ToolkitObjectType toolkitObjectType, String name) {
    lock(toolkitObjectType, name);
    try {
      localCache.remove(name);
      for (ToolkitTypeRoot<ToolkitObjectStripe<S>> root : roots) {
        root.removeClusteredObject(name);
      }
    } finally {
      unlock(toolkitObjectType, name);
    }
  }

  private String generateLockIdentifier(ToolkitObjectType toolkitObjectType, String name) {
    return "@__tc_toolkit_object_lock_" + toolkitObjectType.name() + "_" + name;
  }

  private void lock(ToolkitObjectType toolkitObjectType, String name) {
    String lockID = generateLockIdentifier(toolkitObjectType, name);
    ManagerUtil.beginLock(lockID, LockLevel.WRITE_LEVEL);
  }

  private void unlock(ToolkitObjectType toolkitObjectType, String name) {
    String lockID = generateLockIdentifier(toolkitObjectType, name);
    ManagerUtil.commitLock(lockID, LockLevel.WRITE_LEVEL);
  }

  @Override
  public void applyDestroy(String name) {
    this.localCache.remove(name);
  }

  /**
   * {@inheritDoc}
   */
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
