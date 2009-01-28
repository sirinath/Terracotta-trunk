/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.server.appserver.unit;

import com.meterware.httpunit.WebConversation;
import com.tc.test.AppServerInfo;
import com.tc.test.server.appserver.deployment.AbstractTwoServerDeploymentTest;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.test.server.appserver.deployment.WebApplicationServer;
import com.tc.test.server.util.TcConfigBuilder;
import com.tctest.webapp.servlets.SessionIDIntegrityTestServlet;

import java.util.regex.Pattern;

import junit.framework.Test;

/**
 * Test to make sure session id is preserved with Terracotta
 */
public class SessionIDIntegrityTest extends AbstractTwoServerDeploymentTest {
  protected static final String CONTEXT = "SessionIDIntegrityTest";
  private static final String   MAPPING = "SessionIDIntegrityTestServlet";

  public static Test suite() {
    return new SessionIDIntegrityTestSetup();
  }

  public final void testSessionId() throws Exception {
    WebConversation wc = new WebConversation();

    assertEquals("OK", request(server0, "cmd=insert", wc));

    String server0_session_id = wc.getCookieValue("JSESSIONID");
    System.out.println("Server0 session id: " + server0_session_id);
    assertSessionIdIntegrity(server0_session_id, "server_0");

    assertEquals("OK", request(server1, "cmd=query", wc));

    String server1_session_id = wc.getCookieValue("JSESSIONID");
    System.out.println("Server1 session id: " + server1_session_id);
    assertSessionIdIntegrity(server1_session_id, "server_1");
  }

  private void assertSessionIdIntegrity(String sessionId, String extra_id) {
    int appId = appServerInfo().getId();

    System.out.println("sessionId='" + sessionId + "' extra_id='" + extra_id + "'");

    switch (appId) {
      case AppServerInfo.JETTY:
      case AppServerInfo.TOMCAT:
      case AppServerInfo.WASCE:
      case AppServerInfo.JBOSS:
        assertTrue(sessionId.endsWith("." + extra_id));
        break;
      case AppServerInfo.WEBLOGIC:
        assertTrue(Pattern.matches("\\S+!-?\\d+", sessionId));
        break;
      case AppServerInfo.GLASSFISH:
        assertTrue(Pattern.matches("[A-F0-9]+.\\d", sessionId));
        break;
      default:
        throw new RuntimeException("Appserver id [" + appId + "] is missing in this test");
    }
  }

  private String request(WebApplicationServer server, String params, WebConversation wc) throws Exception {
    return server.ping("/" + CONTEXT + "/" + MAPPING + "?" + params, wc).getText().trim();
  }

  protected static class SessionIDIntegrityTestSetup extends TwoServerTestSetup {
    public SessionIDIntegrityTestSetup() {
      this(SessionIDIntegrityTest.class, CONTEXT);
    }

    public SessionIDIntegrityTestSetup(Class testClass, String context) {
      super(testClass, context);
    }

    protected void configureWar(DeploymentBuilder builder) {
      builder.addServlet(MAPPING, "/" + MAPPING + "/*", SessionIDIntegrityTestServlet.class, null, false);
    }

    protected void configureTcConfig(TcConfigBuilder clientConfig) {
      if (isSessionLockingTrue()) clientConfig.addWebApplication(CONTEXT);
      else clientConfig.addWebApplicationWithoutSessionLocking(CONTEXT);
    }
  }
}
