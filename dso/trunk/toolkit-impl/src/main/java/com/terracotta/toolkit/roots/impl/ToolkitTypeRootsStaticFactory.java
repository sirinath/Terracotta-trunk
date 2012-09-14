/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.roots.impl;

import org.terracotta.toolkit.object.ToolkitObject;

import com.tc.net.GroupID;
import com.tc.object.bytecode.PlatformService;
import com.terracotta.toolkit.object.TCToolkitObject;
import com.terracotta.toolkit.object.ToolkitObjectStripe;
import com.terracotta.toolkit.roots.AggregateToolkitTypeRoot;
import com.terracotta.toolkit.roots.ToolkitTypeRoot;
import com.terracotta.toolkit.roots.ToolkitTypeRootsFactory;
import com.terracotta.toolkit.roots.impl.RootsUtil.RootObjectCreator;
import com.terracotta.toolkit.type.DistributedToolkitType;
import com.terracotta.toolkit.type.DistributedToolkitTypeFactory;
import com.terracotta.toolkit.type.IsolatedToolkitTypeFactory;
import com.terracotta.toolkit.util.collections.WeakValueMapManager;

public class ToolkitTypeRootsStaticFactory implements ToolkitTypeRootsFactory {

  private final WeakValueMapManager manager;

  public ToolkitTypeRootsStaticFactory(WeakValueMapManager manager) {
    this.manager = manager;
  }

  @Override
  public <T extends ToolkitObject, S extends TCToolkitObject> AggregateIsolatedToolkitTypeRoot<T, S> createAggregateIsolatedTypeRoot(String name,
                                                                                                                                     IsolatedToolkitTypeFactory<T, S> isolatedTypeFactory,
                                                                                                                                     PlatformService platformService) {
    GroupID[] gids = platformService.getGroupIDs();
    ToolkitTypeRoot<S>[] roots = new ToolkitTypeRoot[gids.length];
    for (int i = 0; i < gids.length; i++) {
      roots[i] = lookupOrCreateRootInGroup(platformService, gids[i], name);
    }
    return new AggregateIsolatedToolkitTypeRoot<T, S>(roots, isolatedTypeFactory, manager.createWeakValueMap(),
                                                      platformService);
  }

  private static ToolkitTypeRoot lookupOrCreateRootInGroup(PlatformService platformService, GroupID gid, String name) {
    return RootsUtil.lookupOrCreateRootInGroup(platformService, gid, name,
                                               new RootObjectCreator<ToolkitTypeRootImpl>() {
      @Override
      public ToolkitTypeRootImpl create() {
        return new ToolkitTypeRootImpl();
      }
    });
  }

  @Override
  public <T extends DistributedToolkitType<S>, S extends TCToolkitObject> AggregateToolkitTypeRoot<T, S> createAggregateDistributedTypeRoot(String rootName,
                                                                                                                                            DistributedToolkitTypeFactory<T, S> aggregateToolkitTypeFactory,
                                                                                                                                            PlatformService platformService) {
    GroupID[] gids = platformService.getGroupIDs();
    ToolkitTypeRoot<ToolkitObjectStripe<S>>[] roots = new ToolkitTypeRoot[gids.length];
    for (int i = 0; i < gids.length; i++) {
      roots[i] = lookupOrCreateRootInGroup(platformService, gids[i], rootName);
    }
    return new AggregateDistributedToolkitTypeRoot(roots, aggregateToolkitTypeFactory, manager.createWeakValueMap(),
                                                   platformService);
  }
}
