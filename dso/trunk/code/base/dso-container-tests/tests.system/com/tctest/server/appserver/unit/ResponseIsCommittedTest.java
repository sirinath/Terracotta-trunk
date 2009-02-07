/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.server.appserver.unit;

import org.apache.commons.lang.ClassUtils;

import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;
import com.tc.test.server.appserver.deployment.AbstractOneServerDeploymentTest;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.util.TcConfigBuilder;
import com.tctest.webapp.servlets.ResponseIsCommittedServlet;

import javax.servlet.http.HttpServletResponse;

import junit.framework.Test;

public class ResponseIsCommittedTest extends AbstractOneServerDeploymentTest {

  protected static final String CONTEXT = "ResponseIsCommitted";
  private static final String   MAPPING = "Servlet";

  public static Test suite() {
    return new ResponseIsCommittedTestTestSetup();
  }

  public void test() throws Exception {
    WebResponse response;

    response = request("sendRedirect");
    assertEquals(HttpServletResponse.SC_MOVED_TEMPORARILY, response.getResponseCode());
    response = request("check-sendRedirect");
    assertEquals("true", response.getText().trim());

    response = request("sendError1");
    assertEquals(ResponseIsCommittedServlet.SEND_ERROR_CODE, response.getResponseCode());
    response = request("check-sendError1");
    assertEquals("true", response.getText().trim());

    response = request("sendError2");
    assertEquals(ResponseIsCommittedServlet.SEND_ERROR_CODE, response.getResponseCode());
    response = request("check-sendError2");
    assertEquals("true", response.getText().trim());
  }

  private WebResponse request(String command) throws Exception {
    WebConversation wc = new WebConversation();
    wc.getClientProperties().setAutoRedirect(false);
    String url = "http://localhost:" + server0.getPort() + "/" + CONTEXT + "/" + MAPPING + "?cmd=" + command;
    wc.setExceptionsThrownOnErrorStatus(false);

    System.err.println("making request: " + url);

    return wc.getResponse(url);
  }

  protected static class ResponseIsCommittedTestTestSetup extends OneServerTestSetup {

    public ResponseIsCommittedTestTestSetup() {
      this(ResponseIsCommittedTest.class, CONTEXT);
    }

    public ResponseIsCommittedTestTestSetup(Class testClass, String context) {
      super(testClass, context);
    }

    protected void configureWar(DeploymentBuilder builder) {
      builder.addServlet(ClassUtils.getShortClassName(ResponseIsCommittedServlet.class), "/" + MAPPING + "/*",
                         ResponseIsCommittedServlet.class, null, false);
    }

    protected void configureTcConfig(TcConfigBuilder tcConfigBuilder) {
      if (isSessionLockingTrue()) tcConfigBuilder.addWebApplication(CONTEXT);
      else tcConfigBuilder.addWebApplicationWithoutSessionLocking(CONTEXT);
    }

  }

}
