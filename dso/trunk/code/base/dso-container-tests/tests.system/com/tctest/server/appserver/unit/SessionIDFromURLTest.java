/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.server.appserver.unit;

import com.meterware.httpunit.WebConversation;
import com.tc.test.server.appserver.deployment.AbstractOneServerDeploymentTest;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.util.TcConfigBuilder;
import com.tctest.webapp.servlets.SessionIDFromURLServlet;

import junit.framework.Test;

public class SessionIDFromURLTest extends AbstractOneServerDeploymentTest {
  protected static final String CONTEXT = "SessionIDFromURL";
  private static final String   SERVLET = "SessionIDFromURLServlet";

  public static Test suite() {
    return new SessionIDFromURLTestSetup();
  }

  public void testURLSessionId() throws Exception {
    String encodedURL;

    encodedURL = server0.ping("/" + CONTEXT + "/" + SERVLET + "?cmd=new").getText().trim();
    encodedURL = "http://localhost:" + server0.getPort() + "/" + CONTEXT + "/" + encodedURL + "?cmd=query";
    assertEquals("OK", new WebConversation().getResponse(encodedURL).getText().trim());

    encodedURL = server0.ping("/" + CONTEXT + "/" + SERVLET + "?cmd=new&abs=true").getText().trim();
    encodedURL = encodedURL.concat("?cmd=query");
    assertEquals("OK", new WebConversation().getResponse(encodedURL).getText().trim());
  }

  protected static class SessionIDFromURLTestSetup extends OneServerTestSetup {

    public SessionIDFromURLTestSetup() {
      this(SessionIDFromURLTest.class, CONTEXT);
    }

    public SessionIDFromURLTestSetup(Class testClass, String context) {
      super(testClass, context);
    }

    protected void configureWar(DeploymentBuilder builder) {
      builder.addServlet("SessionIDFromURLServlet", "/" + SERVLET + "/*", SessionIDFromURLServlet.class, null, false);
    }

    protected void configureTcConfig(TcConfigBuilder tcConfigBuilder) {
      if (isSessionLockingTrue()) tcConfigBuilder.addWebApplication(CONTEXT);
      else tcConfigBuilder.addWebApplicationWithoutSessionLocking(CONTEXT);
    }
  }
}
