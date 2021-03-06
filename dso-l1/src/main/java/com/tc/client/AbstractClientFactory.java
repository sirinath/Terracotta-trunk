/* 
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at 
 *
 *      http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Terracotta Platform.
 *
 * The Initial Developer of the Covered Software is 
 *      Terracotta, Inc., a Software AG company
 */
package com.tc.client;

import com.tc.abortable.AbortableOperationManager;
import com.tc.lang.TCThreadGroup;
import com.tc.license.ProductID;
import com.tc.net.core.security.TCSecurityManager;
import com.tc.object.DistributedObjectClient;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.PreparedComponentsFromL2Connection;
import com.tc.object.loaders.ClassProvider;
import com.tc.platform.rejoin.RejoinManagerInternal;
import com.tc.util.UUID;
import com.tc.util.factory.AbstractFactory;
import com.tcclient.cluster.DsoClusterInternal;

import java.util.Map;

public abstract class AbstractClientFactory extends AbstractFactory {
  private static String FACTORY_SERVICE_ID            = "com.tc.client.ClientFactory";
  private static Class  STANDARD_CLIENT_FACTORY_CLASS = StandardClientFactory.class;

  public static AbstractClientFactory getFactory() {
    return (AbstractClientFactory) getFactory(FACTORY_SERVICE_ID, STANDARD_CLIENT_FACTORY_CLASS);
  }

  public abstract DistributedObjectClient createClient(DSOClientConfigHelper config, TCThreadGroup threadGroup,
                                                       ClassProvider classProvider,
                                                       PreparedComponentsFromL2Connection connectionComponents,
                                                       DsoClusterInternal dsoCluster,
                                                       TCSecurityManager securityManager,
                                                       AbortableOperationManager abortableOperationManager,
                                                       RejoinManagerInternal rejoinManager, UUID uuid,
                                                       ProductID productId);

  public abstract TCSecurityManager createClientSecurityManager(Map<String, Object> env);
}
