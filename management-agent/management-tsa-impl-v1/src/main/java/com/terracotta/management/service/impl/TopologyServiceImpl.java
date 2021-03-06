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
package com.terracotta.management.service.impl;

import static com.terracotta.management.resource.services.utils.ProductIdConverter.stringsToProductsIds;

import org.terracotta.management.ServiceExecutionException;

import com.terracotta.management.resource.ClientEntity;
import com.terracotta.management.resource.ServerGroupEntity;
import com.terracotta.management.resource.TopologyEntity;
import com.terracotta.management.service.OperatorEventsService;
import com.terracotta.management.service.TopologyService;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * @author Ludovic Orban
 */
public class TopologyServiceImpl implements TopologyService {

  private final ServerManagementService serverManagementService;
  private final ClientManagementService clientManagementService;
  private final OperatorEventsService operatorEventsService;

  public TopologyServiceImpl(ServerManagementService serverManagementService, ClientManagementService clientManagementService, OperatorEventsService operatorEventsService) {
    this.serverManagementService = serverManagementService;
    this.clientManagementService = clientManagementService;
    this.operatorEventsService = operatorEventsService;
  }

  private Collection<ServerGroupEntity> getServerGroups(Set<String> serverNames) throws ServiceExecutionException {
    return serverManagementService.getServerGroups(serverNames);
  }

  private Collection<ClientEntity> getClients(Set<String> clientProductIds) throws ServiceExecutionException {
    return clientManagementService.getClients(null, stringsToProductsIds(clientProductIds));
  }

  @Override
  public Collection<TopologyEntity> getTopologies(Set<String> productIDs) throws ServiceExecutionException {
     TopologyEntity result = new TopologyEntity();
     result.setVersion(this.getClass().getPackage().getImplementationVersion());
     result.getServerGroupEntities().addAll(this.getServerGroups(null));
     result.getClientEntities().addAll(this.getClients(productIDs));
     result.setUnreadOperatorEventCount(operatorEventsService.getUnreadCount(null));
     return Collections.singleton(result);
  }

  @Override
  public Collection<TopologyEntity> getServerTopologies(Set<String> serverNames) throws ServiceExecutionException {
     TopologyEntity result = new TopologyEntity();
     result.setVersion(this.getClass().getPackage().getImplementationVersion());
     result.getServerGroupEntities().addAll(this.getServerGroups(serverNames));
     result.setUnreadOperatorEventCount(operatorEventsService.getUnreadCount(serverNames));
     return Collections.singleton(result);
  }

  @Override
  public Collection<TopologyEntity> getConnectedClients(Set<String> productIDs) throws ServiceExecutionException {
    TopologyEntity result = new TopologyEntity();
    result.setVersion(this.getClass().getPackage().getImplementationVersion());
    result.getClientEntities().addAll(this.getClients(productIDs));
    return Collections.singleton(result);
  }

  @Override
  public Collection<TopologyEntity> getUnreadOperatorEventCount(Set<String> serverNames) throws ServiceExecutionException {
    TopologyEntity result = new TopologyEntity();
    result.setVersion(this.getClass().getPackage().getImplementationVersion());
    result.setUnreadOperatorEventCount(operatorEventsService.getUnreadCount(serverNames));
    return Collections.singleton(result);
  }

}
