/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.type;

import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.object.ToolkitObject;

import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.object.TCToolkitObject;

public interface IsolatedToolkitTypeFactory<T extends ToolkitObject, S extends TCToolkitObject> {

  /**
   * Used to create the unclustered type after faulting in the TCClusteredObject
   */
  T createIsolatedToolkitType(ToolkitObjectFactory<T> factory, String name, Configuration config, S tcClusteredObject);

  /**
   * Used to create the TCClusteredObject to back the type
   */
  S createTCClusteredObject(Configuration config);

}
