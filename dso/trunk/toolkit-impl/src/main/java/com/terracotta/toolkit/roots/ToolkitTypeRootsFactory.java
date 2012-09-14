/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.roots;

import org.terracotta.toolkit.object.ToolkitObject;

import com.tc.object.bytecode.PlatformService;
import com.terracotta.toolkit.object.TCToolkitObject;
import com.terracotta.toolkit.roots.impl.AggregateIsolatedToolkitTypeRoot;
import com.terracotta.toolkit.type.DistributedToolkitType;
import com.terracotta.toolkit.type.DistributedToolkitTypeFactory;
import com.terracotta.toolkit.type.IsolatedToolkitTypeFactory;

/**
 * A factory responsible for creating different types of roots
 */
public interface ToolkitTypeRootsFactory {

  <T extends ToolkitObject, S extends TCToolkitObject> AggregateIsolatedToolkitTypeRoot<T, S> createAggregateIsolatedTypeRoot(String name,
                                                                                                                              IsolatedToolkitTypeFactory<T, S> factory,
                                                                                                                              PlatformService platformService);

  <T extends DistributedToolkitType<S>, S extends TCToolkitObject> AggregateToolkitTypeRoot<T, S> createAggregateDistributedTypeRoot(String rootName,
                                                                                                                                     DistributedToolkitTypeFactory<T, S> factory,
                                                                                                                                     PlatformService platformService);

}
