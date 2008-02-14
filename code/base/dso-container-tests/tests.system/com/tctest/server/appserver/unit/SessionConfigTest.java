/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.server.appserver.unit;

import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;
import com.tc.test.AppServerInfo;
import com.tc.test.server.appserver.deployment.AbstractDeploymentTest;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.test.server.appserver.deployment.GenericServer;
import com.tc.test.server.appserver.deployment.ServerTestSetup;
import com.tc.test.server.appserver.deployment.WebApplicationServer;
import com.tc.test.server.appserver.was6x.Was6xAppServer;
import com.tc.test.server.util.TcConfigBuilder;
import com.tc.util.io.TCFileUtils;
import com.tctest.webapp.servlets.SessionConfigServlet;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import junit.framework.Test;

/**
 * Test session-descriptor setting http://edocs.bea.com/wls/docs81/webapp/weblogic_xml.html#1038173
 * http://edocs.bea.com/wls/docs90/webapp/weblogic_xml.html#1071982
 * 
 * @author hhuynh
 */
public class SessionConfigTest extends AbstractDeploymentTest {
  private static final String  RESOURCE_ROOT      = "/com/tctest/server/appserver/unit/sessionconfigtest";
  private static final String  CONTEXT            = "SessionConfigTest";
  private static final String  MAPPING            = "app";

  private DeploymentBuilder    builder;
  private TcConfigBuilder      tcConfigBuilder;
  private WebApplicationServer server;
  private Map                  descriptors        = new HashMap();
  private Map                  extraServerJvmArgs = new HashMap();
  private boolean              weblogicOrWebsphere;

  public SessionConfigTest() {
    weblogicOrWebsphere = appServerInfo().getId() == AppServerInfo.WEBLOGIC
                          || appServerInfo().getId() == AppServerInfo.WEBSPHERE;
  }

  public static Test suite() {
    return new ServerTestSetup(SessionConfigTest.class);
  }

  // CookieEnabled = false
  public void testCookieDisabled() throws Exception {
    if (!weblogicOrWebsphere) return;
    descriptors.put("wl81", "weblogic81a.xml");
    descriptors.put("wl92", "weblogic92a.xml");
    descriptors.put("was61", "websphere61a.xml");
    init();

    WebConversation conversation = new WebConversation();

    WebResponse response = request(server, "testcase=testCookieDisabled&hit=0", conversation);
    assertEquals("OK", response.getText().trim());

    response = request(server, "testcase=testCookieDisabled&hit=1", conversation);
    assertEquals("OK", response.getText().trim());
  }

  // CookieEnabled = false, URLRewritingEnabled = false
  public void testUrlRewritingDisabled() throws Exception {
    if (!weblogicOrWebsphere) return;
    descriptors.put("wl81", "weblogic81b.xml");
    descriptors.put("wl92", "weblogic92b.xml");
    descriptors.put("was61", "websphere61b.xml");
    init();

    WebResponse response = request(server, "testcase=testUrlRewritingDisabled", new WebConversation());
    System.out.println("Response: " + response.getText());
    assertEquals("OK", response.getText().trim());
  }

  // TrackingEnabled = false -- only applicable to Weblogic
  public void testTrackingDisabled() throws Exception {
    if (!weblogicOrWebsphere) return;
    if (appServerInfo().getId() != AppServerInfo.WEBLOGIC) { return; }
    descriptors.put("wl81", "weblogic81c.xml");
    descriptors.put("wl92", "weblogic92c.xml");
    init();

    WebConversation wc = new WebConversation();
    WebResponse response = request(server, "testcase=testTrackingDisabled&hit=0", wc);
    System.out.println("Response: " + response.getText());
    assertEquals("OK", response.getText().trim());

    response = request(server, "testcase=testTrackingDisabled&hit=1", wc);
    System.out.println("Response: " + response.getText());
    assertEquals("OK", response.getText().trim());
  }

  // IDLength = 69 -- only applicable to Weblogic
  public void testIdLength() throws Exception {
    if (appServerInfo().getId() != AppServerInfo.WEBLOGIC) { return; }
    descriptors.put("wl81", "weblogic81d.xml");
    descriptors.put("wl92", "weblogic92d.xml");
    init();

    WebConversation wc = new WebConversation();
    WebResponse response = request(server, "", wc);
    assertEquals("OK", response.getText().trim());
    int idLength = wc.getCookieValue("JSESSIONID").indexOf("!");
    System.out.println(wc.getCookieValue("JSESSIONID") + ", length = " + idLength);
    assertEquals(69, idLength);
  }

  // test session-timeout field in web.xml
  public void testSessionTimeOutNegative() throws Exception {
    initWithSessionTimeout(-1);
    WebConversation wc = new WebConversation();
    WebResponse response = request(server, "testcase=testSessionTimeOutNegative&hit=0", wc);
    long actual = Long.parseLong(response.getText().replaceAll("[^\\d+-]", ""));
    System.out.println("actual: " + actual);
    assertTrue(actual < 0);

    // even though negative number should dictate the session never timeouts
    // we only test it for 1 minute to save time.
    Thread.sleep(60 * 1000);
    response = request(server, "testcase=testSessionTimeOutNegative&hit=1", wc);
    assertEquals("OK", response.getText().trim());
  }

  // test session-timeout field in web.xml
  public void testSessionTimeOutArbitrary() throws Exception {
    int someBigValue = Integer.MAX_VALUE / 60;
    initWithSessionTimeout(someBigValue);
    WebConversation wc = new WebConversation();
    WebResponse response = request(server, "testcase=testSessionTimeOutArbitrary", wc);
    int actual = Integer.parseInt(response.getText().replaceAll("[^\\d+]", ""));
    assertEquals(someBigValue * 60, actual);
  }

  public void testSessionTimeOutFromTCProperties() throws Exception {
    if (appServerInfo().getId() == AppServerInfo.JETTY) return;
    extraServerJvmArgs.put("com.tc.session.maxidle.seconds", String.valueOf(Integer.MAX_VALUE));
    init();
    WebConversation wc = new WebConversation();
    WebResponse response = request(server, "testcase=testSessionTimeOutArbitrary", wc);
    int actual = Integer.parseInt(response.getText().replaceAll("[^\\d+]", ""));
    assertEquals(Integer.MAX_VALUE, actual);
  }

  private void initWithSessionTimeout(int timeOutInMinutes) throws Exception {
    createTestDeployment();
    builder.addSessionConfig("session-timeout", String.valueOf(timeOutInMinutes));
    createAndStartAppServer();
  }

  private WebResponse request(WebApplicationServer appserver, String params, WebConversation con) throws Exception {
    return appserver.ping("/" + CONTEXT + "/" + MAPPING + "?" + params, con);
  }

  private DeploymentBuilder createTestDeployment() {
    tcConfigBuilder = new TcConfigBuilder();
    tcConfigBuilder.addWebApplication(CONTEXT);

    // prepare test war
    builder = makeDeploymentBuilder(CONTEXT + ".war");
    builder.addServlet("SessionConfigServlet", "/" + MAPPING + "/*", SessionConfigServlet.class, null, false);

    return builder;
  }

  private void createAndStartAppServer() throws Exception {
    server = makeWebApplicationServer(tcConfigBuilder);
    addSessionDescriptor();
    server.addWarDeployment(builder.makeDeployment(), CONTEXT);
    if (extraServerJvmArgs.size() > 0) {
      for (Iterator it = extraServerJvmArgs.entrySet().iterator(); it.hasNext();) {
        Map.Entry e = (Map.Entry) it.next();
        server.getServerParameters().appendSysProp((String) e.getKey(), (String) e.getValue());
      }
    }
    server.start();
  }

  private void init() throws Exception {
    createTestDeployment();
    createAndStartAppServer();
  }

  private void addSessionDescriptor() throws Exception {
    if (descriptors.size() == 0) return;

    switch (appServerInfo().getId()) {
      case AppServerInfo.WEBLOGIC:
        if (appServerInfo().getMajor().equals("8")) {
          builder.addResourceFullpath(RESOURCE_ROOT, (String) descriptors.get("wl81"), "WEB-INF/weblogic.xml");
        }
        if (appServerInfo().getMajor().equals("9")) {
          builder.addResourceFullpath(RESOURCE_ROOT, (String) descriptors.get("wl92"), "WEB-INF/weblogic.xml");
        }
        break;
      case AppServerInfo.WEBSPHERE:
        Was6xAppServer wasServer = (Was6xAppServer) ((GenericServer) server).getAppServer();
        wasServer.setExtraScript(TCFileUtils.getResourceFile(RESOURCE_ROOT + "/" + (String) descriptors.get("was61")));
        break;
      default:
        break;
    }
  }

}
