/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import net.sf.ehcache.Cache;
import net.sf.jsr107cache.CacheListener;

import org.apache.commons.logging.LogFactory;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.log4j.Logger;

import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;
import com.tc.test.AppServerInfo;
import com.tc.test.server.appserver.deployment.AbstractTwoServerDeploymentTest;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.test.server.appserver.deployment.WebApplicationServer;
import com.tc.test.server.util.TcConfigBuilder;
import com.tc.util.TIMUtil;

import java.io.PrintWriter;
import java.util.Date;

import junit.framework.Test;

public class ContainerHibernate325Test extends AbstractTwoServerDeploymentTest {
  private static final String CONFIG_FILE_FOR_TEST = "/tc-config-files/hibernate-tc-config.xml";

  public static Test suite() {
    return new ContainerHibernateTestSetup();
  }

  public ContainerHibernate325Test() {
    if (shouldDisable()) {
      disableAllUntil(new Date(Long.MAX_VALUE));
    }
  }

  public boolean shouldDisable() {
    // MNK-287
    int id = appServerInfo().getId();
    boolean wasceOrWebSphere = (id == AppServerInfo.WASCE || id == AppServerInfo.WEBSPHERE);
    return super.shouldDisable() || wasceOrWebSphere;
  }

  public void testHibernate() throws Exception {
    WebConversation conversation = new WebConversation();

    WebResponse response1 = request(server0, "server=server0", conversation);
    assertEquals("OK", response1.getText().trim());

    WebResponse response2 = request(server1, "server=server1", conversation);
    assertEquals("OK", response2.getText().trim());
  }

  private WebResponse request(WebApplicationServer server, String params, WebConversation con) throws Exception {
    return server.ping("/events/ContainerHibernateTestServlet?" + params, con);
  }

  private static class ContainerHibernateTestSetup extends TwoServerTestSetup {
    private NetworkServerControl derbyServer;

    private ContainerHibernateTestSetup() {
      super(ContainerHibernate325Test.class, CONFIG_FILE_FOR_TEST, "events");
    }

    protected void configureWar(DeploymentBuilder builder) {
      builder.addDirectoryOrJARContainingClass(org.hibernate.SessionFactory.class); // hibernate*.jar
      builder.addDirectoryOrJARContainingClass(org.dom4j.Node.class); // domj4*.jar
      builder.addDirectoryOrJARContainingClass(net.sf.cglib.core.ClassInfo.class); // cglib-nodep*.jar
      builder.addDirectoryOrJARContainingClass(javax.transaction.Transaction.class); // jta*.jar
      builder.addDirectoryOrJARContainingClass(org.apache.commons.collections.Buffer.class); // 
      builder.addDirectoryOrJARContainingClass(org.apache.derby.jdbc.ClientDriver.class); // derby*.jar
      builder.addDirectoryOrJARContainingClass(antlr.Tool.class); // antlr*.jar
      builder.addDirectoryOrJARContainingClass(Cache.class); // ehcache-1.3.0.jar
      builder.addDirectoryOrJARContainingClass(CacheListener.class); // jsr107cache-1.0.jar

      if (appServerInfo().getId() != AppServerInfo.JBOSS) {
        builder.addDirectoryOrJARContainingClass(Logger.class); // log4j
        builder.addDirectoryOrJARContainingClass(LogFactory.class); // common-loggings
      }

      builder.addResource("/com/tctest/server/appserver/unit", "hibernate.cfg.xml", "WEB-INF/classes");
      builder.addResource("/com/tctest/server/appserver/unit", "ehcache13.xml", "WEB-INF/classes");
      builder.addResource("/com/tctest/server/appserver/unit", "Event.hbm.xml", "WEB-INF/classes");
      builder.addResource("/com/tctest/server/appserver/unit", "Person.hbm.xml", "WEB-INF/classes");
      builder.addResource("/com/tctest/server/appserver/unit", "PhoneNumber.hbm.xml", "WEB-INF/classes");
      builder.addResource("/com/tctest/server/appserver/unit", "Account.hbm.xml", "WEB-INF/classes");

      builder.addResource("/com/tctest/server/appserver/unit/containerhibernatetest", "jboss-web.xml", "WEB-INF");

      builder.addServlet("ContainerHibernateTestServlet", "/ContainerHibernateTestServlet/*",
                         ContainerHibernateTestServlet.class, null, true);
    }

    protected void configureTcConfig(TcConfigBuilder clientConfig) {
      clientConfig.addModule(TIMUtil.EHCACHE_1_3, TIMUtil.getVersion(TIMUtil.EHCACHE_1_3));
      clientConfig.addModule(TIMUtil.HIBERNATE_3_2_5, TIMUtil.getVersion(TIMUtil.HIBERNATE_3_2_5));
    }

    public void setUp() throws Exception {
      derbyServer = new NetworkServerControl();
      derbyServer.start(new PrintWriter(System.out));
      int tries = 0;
      while (tries < 5) {
        try {
          Thread.sleep(500);
          derbyServer.ping();
          break;
        } catch (Exception e) {
          tries++;
        }
      }
      if (tries == 5) { throw new Exception("Failed to start Derby!"); }

      super.setUp();
    }

    public void tearDown() throws Exception {
      super.tearDown();
      derbyServer.shutdown();
    }

  }
}
