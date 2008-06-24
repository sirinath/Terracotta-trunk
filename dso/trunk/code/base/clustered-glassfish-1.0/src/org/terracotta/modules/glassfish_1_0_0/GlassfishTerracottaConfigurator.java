package org.terracotta.modules.glassfish_1_0_0;

import org.osgi.framework.BundleContext;
import org.terracotta.modules.configuration.TerracottaConfiguratorModule;

import com.tc.object.bytecode.DelegateMethodAdapter;

public class GlassfishTerracottaConfigurator extends TerracottaConfiguratorModule {

  protected final void addInstrumentation(final BundleContext context) {
    configHelper.addCustomAdapter("com.sun.jdo.api.persistence.model.RuntimeModel", new RuntimeModelAdapter());
    configHelper.addCustomAdapter("com.sun.enterprise.server.PEMain", new PEMainAdapter());
    configHelper.addCustomAdapter("org.apache.catalina.core.ApplicationDispatcher", new ApplicationDispatcherAdapter());
    configHelper.addCustomAdapter("org.apache.catalina.core.ApplicationDispatcherForward",
                                  new ApplicationDispatcherForwardAdapter());
    final DelegateMethodAdapter delegateMethodAdapter = new DelegateMethodAdapter("org.apache.catalina.HttpRequest",
                                                                                  "req");
    configHelper.addCustomAdapter("com.tc.tomcat50.session.SessionRequest50", delegateMethodAdapter);
  }
}
