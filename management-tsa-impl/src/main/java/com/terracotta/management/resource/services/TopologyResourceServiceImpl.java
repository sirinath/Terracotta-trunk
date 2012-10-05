/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.management.resource.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.management.ServiceExecutionException;
import org.terracotta.management.ServiceLocator;
import org.terracotta.management.resource.services.validator.RequestValidator;

import com.terracotta.management.resource.ClientEntity;
import com.terracotta.management.resource.TopologyEntity;
import com.terracotta.management.resource.services.validator.TSARequestValidator;
import com.terracotta.management.service.TopologyService;

import java.util.Collection;
import java.util.Collections;

import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

/**
 * @author Ludovic Orban
 */
@Path("/agents/topologies")
public class TopologyResourceServiceImpl implements TopologyResourceService {

  private static final Logger LOG = LoggerFactory.getLogger(TopologyResourceServiceImpl.class);

  private final TopologyService topologyService;
  private final RequestValidator requestValidator;

  public TopologyResourceServiceImpl() {
    this.topologyService = ServiceLocator.locate(TopologyService.class);
    this.requestValidator = ServiceLocator.locate(TSARequestValidator.class);
  }

  @Override
  public Collection<TopologyEntity> getServerTopologies(UriInfo info) {
    LOG.info(String.format("Invoking TopologyServiceImpl.getServerTopologies: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      return Collections.singleton(topologyService.getTopology());
    } catch (ServiceExecutionException e) {
      LOG.error("Failed to get TSA topologies.", e.getCause());
      throw new WebApplicationException(
          Response.status(Response.Status.BAD_REQUEST).entity(e.getCause().getMessage()).build());
    }
  }

  @Override
  public Collection<ClientEntity> getConnectedClients(@Context UriInfo info) {
    LOG.info(String.format("Invoking TopologyServiceImpl.getConnectedClients: %s", info.getRequestUri()));

    requestValidator.validateSafe(info);

    try {
      return topologyService.getClients();
    } catch (ServiceExecutionException e) {
      LOG.error("Failed to get TSA clients.", e.getCause());
      throw new WebApplicationException(
          Response.status(Response.Status.BAD_REQUEST).entity(e.getCause().getMessage()).build());
    }
  }

}
