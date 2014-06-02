package com.terracotta.management;

import net.sf.ehcache.management.resource.services.ElementsResourceServiceImplV2;
import net.sf.ehcache.management.service.CacheManagerServiceV2;
import net.sf.ehcache.management.service.CacheServiceV2;
import net.sf.ehcache.management.service.EntityResourceFactoryV2;

import org.terracotta.management.application.DefaultApplicationV2;
import org.terracotta.management.resource.services.AgentServiceV2;
import org.terracotta.management.resource.services.validator.RequestValidator;
import org.terracotta.session.management.SessionsServiceV2;

import com.terracotta.management.l1bridge.RemoteAgentServiceV2;
import com.terracotta.management.l1bridge.RemoteRequestValidator;
import com.terracotta.management.l1bridge.RemoteServiceStubGenerator;
import com.terracotta.management.resource.services.AllEventsResourceService;
import com.terracotta.management.resource.services.BackupResourceServiceImplV2;
import com.terracotta.management.resource.services.ConfigurationResourceServiceImplV2;
import com.terracotta.management.resource.services.DiagnosticsResourceServiceImplV2;
import com.terracotta.management.resource.services.JmxResourceServiceImplV2;
import com.terracotta.management.resource.services.LogsResourceServiceImplV2;
import com.terracotta.management.resource.services.MonitoringResourceServiceImplV2;
import com.terracotta.management.resource.services.OperatorEventsResourceServiceImplV2;
import com.terracotta.management.resource.services.ShutdownResourceServiceImplV2;
import com.terracotta.management.resource.services.TopologyResourceServiceImplV2;
import com.terracotta.management.security.ContextService;
import com.terracotta.management.security.RequestTicketMonitor;
import com.terracotta.management.security.SecurityContextService;
import com.terracotta.management.security.UserService;
import com.terracotta.management.service.BackupServiceV2;
import com.terracotta.management.service.ConfigurationServiceV2;
import com.terracotta.management.service.DiagnosticsServiceV2;
import com.terracotta.management.service.JmxServiceV2;
import com.terracotta.management.service.LicenseServiceV2;
import com.terracotta.management.service.LogsServiceV2;
import com.terracotta.management.service.MonitoringServiceV2;
import com.terracotta.management.service.OperatorEventsServiceV2;
import com.terracotta.management.service.RemoteAgentBridgeService;
import com.terracotta.management.service.ShutdownServiceV2;
import com.terracotta.management.service.TimeoutService;
import com.terracotta.management.service.TopologyServiceV2;
import com.terracotta.management.service.impl.BackupServiceImplV2;
import com.terracotta.management.service.impl.ClientManagementServiceV2;
import com.terracotta.management.service.impl.ConfigurationServiceImplV2;
import com.terracotta.management.service.impl.DiagnosticsServiceImplV2;
import com.terracotta.management.service.impl.JmxServiceImplV2;
import com.terracotta.management.service.impl.LicenseServiceImplV2;
import com.terracotta.management.service.impl.LogsServiceImplV2;
import com.terracotta.management.service.impl.MonitoringServiceImplV2;
import com.terracotta.management.service.impl.OperatorEventsServiceImplV2;
import com.terracotta.management.service.impl.ServerManagementServiceV2;
import com.terracotta.management.service.impl.ShutdownServiceImplV2;
import com.terracotta.management.service.impl.TopologyServiceImplV2;
import com.terracotta.management.service.impl.TsaAgentServiceImplV2;
import com.terracotta.management.service.impl.util.LocalManagementSource;
import com.terracotta.management.service.impl.util.RemoteManagementSource;
import com.terracotta.management.web.resource.services.IdentityAssertionResourceService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

public class ApplicationTsaV2 extends DefaultApplicationV2 implements ApplicationTsaService {

  @Override
  public Set<Class<?>> getResourceClasses() {
    //
    Set<Class<?>> s = new HashSet<Class<?>>(super.getClasses());
    s.add(ElementsResourceServiceImplV2.class);
    s.add(BackupResourceServiceImplV2.class);
    s.add(ConfigurationResourceServiceImplV2.class);
    s.add(DiagnosticsResourceServiceImplV2.class);
    s.add(LogsResourceServiceImplV2.class);
    s.add(MonitoringResourceServiceImplV2.class);
    s.add(OperatorEventsResourceServiceImplV2.class);
    s.add(ShutdownResourceServiceImplV2.class);
    s.add(TopologyResourceServiceImplV2.class);
    s.add(IdentityAssertionResourceService.class);
    s.add(JmxResourceServiceImplV2.class);
    s.add(AllEventsResourceService.class);

    s.add(net.sf.ehcache.management.resource.services.CacheStatisticSamplesResourceServiceImplV2.class);
    s.add(net.sf.ehcache.management.resource.services.CachesResourceServiceImplV2.class);
    s.add(net.sf.ehcache.management.resource.services.CacheManagersResourceServiceImplV2.class);
    s.add(net.sf.ehcache.management.resource.services.CacheManagerConfigsResourceServiceImplV2.class);
    s.add(net.sf.ehcache.management.resource.services.CacheConfigsResourceServiceImplV2.class);
    s.add(org.terracotta.management.resource.services.AgentsResourceServiceImplV2.class);
    s.add(net.sf.ehcache.management.resource.services.QueryResourceServiceImplV2.class);

    s.add(org.terracotta.session.management.SessionsResourceServiceImplV2.class);

    return s;
  }

  @Override
  public Map<Class<?>, Object> getServiceClasses(ThreadPoolExecutor tsaExecutorService, TimeoutService timeoutService,
                                                 LocalManagementSource localManagementSource,
                                                 RemoteManagementSource remoteManagementSource,
                                                 SecurityContextService securityContextService,
                                                 RequestTicketMonitor requestTicketMonitor, UserService userService,
                                                 ContextService contextService,
                                                 RemoteAgentBridgeService remoteAgentBridgeService,
                                                 ThreadPoolExecutor l1BridgeExecutorService) {

    Map<Class<?>, Object> serviceClasses = new HashMap<Class<?>, Object>();

    ServerManagementServiceV2 serverManagementService = new ServerManagementServiceV2(tsaExecutorService,
                                                                                      timeoutService,
                                                                                      localManagementSource,
                                                                                      remoteManagementSource,
                                                                                      securityContextService);
    OperatorEventsServiceImplV2 operatorEventsServiceImplV2 = new OperatorEventsServiceImplV2(serverManagementService);
    ClientManagementServiceV2 clientManagementService = new ClientManagementServiceV2(serverManagementService,
                                                                                      tsaExecutorService,
                                                                                      timeoutService,
                                                                                      localManagementSource,
                                                                                      remoteManagementSource,
                                                                                      securityContextService);
    
    // pure L2 services
    serviceClasses.put(TopologyServiceV2.class, new TopologyServiceImplV2(serverManagementService, clientManagementService, operatorEventsServiceImplV2));
    serviceClasses.put(MonitoringServiceV2.class, new MonitoringServiceImplV2(serverManagementService, clientManagementService));
    serviceClasses.put(DiagnosticsServiceV2.class, new DiagnosticsServiceImplV2(serverManagementService, clientManagementService));
    serviceClasses.put(ConfigurationServiceV2.class, new ConfigurationServiceImplV2(serverManagementService, clientManagementService));
    serviceClasses.put(BackupServiceV2.class, new BackupServiceImplV2(serverManagementService));
    serviceClasses.put(LogsServiceV2.class, new LogsServiceImplV2(serverManagementService));
    serviceClasses.put(OperatorEventsServiceV2.class, operatorEventsServiceImplV2);
    serviceClasses.put(ShutdownServiceV2.class, new ShutdownServiceImplV2(serverManagementService));
    serviceClasses.put(JmxServiceV2.class, new JmxServiceImplV2(serverManagementService));
    serviceClasses.put(LicenseServiceV2.class, new LicenseServiceImplV2(serverManagementService));

    /// L1 bridge and Security Services ///
    RemoteRequestValidator requestValidator = new RemoteRequestValidator(remoteAgentBridgeService);
    RemoteServiceStubGenerator remoteServiceStubGenerator = new RemoteServiceStubGenerator(requestTicketMonitor,
                                                                                           userService, contextService,
                                                                                           requestValidator,
                                                                                           remoteAgentBridgeService,
                                                                                           l1BridgeExecutorService,
                                                                                           timeoutService);

    /// utility services ///
    serviceClasses.put(RequestTicketMonitor.class, requestTicketMonitor);
    serviceClasses.put(ContextService.class, contextService);
    serviceClasses.put(UserService.class, userService);
    serviceClasses.put(RequestValidator.class, requestValidator);
    serviceClasses.put(RemoteAgentBridgeService.class, remoteAgentBridgeService);

    /// Compound Agent Service ///
    RemoteAgentServiceV2 remoteAgentService = new RemoteAgentServiceV2(remoteAgentBridgeService, contextService, l1BridgeExecutorService, requestTicketMonitor, userService, timeoutService);
    serviceClasses.put(AgentServiceV2.class, new TsaAgentServiceImplV2(serverManagementService, remoteAgentBridgeService, remoteAgentService));

    /// Ehcache Services ///
    serviceClasses.put(CacheManagerServiceV2.class, remoteServiceStubGenerator.newRemoteService(CacheManagerServiceV2.class, "Ehcache"));
    serviceClasses.put(CacheServiceV2.class, remoteServiceStubGenerator.newRemoteService(CacheServiceV2.class, "Ehcache"));
    serviceClasses.put(EntityResourceFactoryV2.class, remoteServiceStubGenerator.newRemoteService(EntityResourceFactoryV2.class, "Ehcache"));
    
    /// Sessions Services ///
    serviceClasses.put(SessionsServiceV2.class, remoteServiceStubGenerator.newRemoteService(SessionsServiceV2.class, "Sessions"));

    return serviceClasses;

  }

}