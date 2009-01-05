/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.server.appserver.unit;

import org.apache.commons.logging.LogFactory;
import org.jboss.logging.JBossJDKLogManager;
import org.jboss.mx.util.JBossNotificationBroadcasterSupport;
import org.jboss.system.ServiceMBeanSupport;
import org.jboss.xb.QNameBuilder;

import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;
import com.tc.test.AppServerInfo;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.test.server.appserver.deployment.AbstractTwoServerDeploymentTest;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.test.server.appserver.deployment.JARBuilder;
import com.tc.test.server.appserver.deployment.WebApplicationServer;
import com.tc.test.server.util.TcConfigBuilder;
import com.tc.util.runtime.Vm;
import com.tctest.service.DirectoryMonitor;
import com.tctest.webapp.servlets.JBossSarServlet;

import java.io.File;
import java.util.Date;

import junit.framework.Test;

public class JBossSarTest extends AbstractTwoServerDeploymentTest {
  private static final String CONTEXT = "jbossSar";
  private static final String SERVLET = "JBossSarServlet";

  public JBossSarTest() {
    if (TestConfigObject.getInstance().appServerId() != AppServerInfo.JBOSS || Vm.isJDK14()) {
      disableAllUntil(new Date(Long.MAX_VALUE));
    }
  }

  public static Test suite() {
    return new JBossSarTestSetup(JBossSarTest.class, false);
  }

  public final void testSar() throws Exception {
    doTest();
  }

  private void doTest() throws Exception {
    System.out.println("Hitting jboss servers...");
    WebResponse resp = request(server0, "", new WebConversation());
    System.out.println("server0 response: " + resp.getText());
    assertTrue(resp.getText().startsWith("OK"));
    assertContains("YMCA", resp.getText());

    resp = request(server1, "", new WebConversation());
    System.out.println("server1 response: " + resp.getText());
    assertTrue(resp.getText().startsWith("OK"));
    assertContains("YMCA", resp.getText());
  }

  private File buildSar(boolean parentDelegation) throws Exception {
    File sarFile = getTempFile("directory-monitor.sar");
    JARBuilder sar = new JARBuilder(sarFile.getName(), sarFile.getParentFile());

    sar.addResource("/com/tctest/service", "DirectoryMonitor.class", "com/tctest/service");
    sar.addResource("/com/tctest/service", "DirectoryMonitorMBean.class", "com/tctest/service");
    sar.addResource("/com/tctest/service", "DirectoryMonitor$ScannerThread.class", "com/tctest/service");
    sar.addDirectoryOrJARContainingClass(LogFactory.class);

    if (parentDelegation) {
      sar.addResourceFullpath("/jboss-sar", "jboss-service-true.xml", "META-INF/jboss-service.xml");
    } else {
      sar.addResourceFullpath("/jboss-sar", "jboss-service-false.xml", "META-INF/jboss-service.xml");
    }

    sar.finish();

    return sarFile;
  }

  private WebResponse request(WebApplicationServer server, String params, WebConversation con) throws Exception {
    return server.ping("/" + CONTEXT + "/" + SERVLET + "?" + params, con);
  }

  static class JBossSarTestSetup extends TwoServerTestSetup {

    private final boolean parentDelegation;
    private final Class   testClass;

    public JBossSarTestSetup(Class testClass, boolean parentDelegation) {
      super(testClass, CONTEXT);
      this.testClass = testClass;
      this.parentDelegation = parentDelegation;
    }

    protected void configureServerParamers(StandardAppServerParameters params) {
      try {
        JBossSarTest jbossTest = (JBossSarTest) this.testClass.newInstance();
        File sarFile = jbossTest.buildSar(parentDelegation);
        params.addSar(sarFile);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    protected void configureWar(DeploymentBuilder builder) {
      builder.addDirectoryOrJARContainingClass(DirectoryMonitor.class);
      builder.addDirectoryOrJARContainingClass(ServiceMBeanSupport.class);
      builder.addDirectoryOrJARContainingClass(JBossNotificationBroadcasterSupport.class);
      builder.addDirectoryOrJARContainingClass(JBossJDKLogManager.class);
      builder.addDirectoryOrJARContainingClass(QNameBuilder.class);

      builder.addServlet(SERVLET, "/" + SERVLET + "/*", JBossSarServlet.class, null, true);
    }

    protected void configureTcConfig(TcConfigBuilder clientConfig) {
      if (isSessionLockingTrue()) clientConfig.addWebApplication(CONTEXT);
      else clientConfig.addWebApplicationWithoutSessionLocking(CONTEXT);
      clientConfig.addInstrumentedClass(DirectoryMonitor.class.getName());

      clientConfig.addRoot(DirectoryMonitor.class.getName() + ".list", "list");
      clientConfig.addRoot(JBossSarServlet.class.getName() + ".list", "list");

      clientConfig.addAutoLock("* " + DirectoryMonitor.class.getName() + ".*(..)", "write");
      clientConfig.addAutoLock("* " + JBossSarServlet.class.getName() + ".*(..)", "write");
    }

  }

  static class JBossSarWithoutSLTestSetup extends JBossSarTestSetup {

    public JBossSarWithoutSLTestSetup(Class testClass, boolean parentDelegation) {
      super(testClass, parentDelegation);
    }

    @Override
    public boolean isSessionLockingTrue() {
      return false;
    }
  }

}
