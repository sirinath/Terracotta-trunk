package com.tc.admin;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import com.tc.test.TCTestCase;
import com.tc.util.ProductInfo;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class UpdateCheckRequestTest extends TCTestCase {
  private Server fServer;
  private int    fPort;

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    fServer = new Server(0);
    ServletHolder servletHolder = new ServletHolder(new TestServlet());
    servletHolder.setInitOrder(1);
    ServletContextHandler context = new ServletContextHandler(fServer, "/", ServletContextHandler.SESSIONS);
    context.addServlet(servletHolder, "/test");
    fServer.start();
    fPort = fServer.getConnectors()[0].getLocalPort();
  }

  @Override
  protected void tearDown() throws Exception {
    if (fServer != null) {
      fServer.stop();
    }
    super.tearDown();
  }

  public void testUpdateCheckRequest() throws Exception {
    URL url = getURL();
    HttpClient httpClient = new HttpClient();
    String response = getResponseBody(url, httpClient).trim();
    if (!response.equals("OK")) {
      fail(response);
    }
  }

  public static String getResponseBody(URL url, HttpClient client) throws ConnectException, IOException {
    GetMethod get = new GetMethod(url.toString());

    get.setFollowRedirects(true);
    try {
      int status = client.executeMethod(get);
      if (status != HttpStatus.SC_OK) { throw new ConnectException(
                                                                   "The http client has encountered a status code other than ok for the url: "
                                                                       + url + " status: "
                                                                       + HttpStatus.getStatusText(status)); }
      return get.getResponseBodyAsString();
    } finally {
      get.releaseConnection();
    }
  }

  private URL getURL() throws MalformedURLException {
    URL url = AdminClientPanel.constructCheckURL(ProductInfo.getInstance(), 0);
    return new URL("http://localhost:" + fPort + "/test?" + url.getQuery());
  }
}

class TestServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("text/html");
    response.setStatus(HttpServletResponse.SC_OK);

    String[] expectedParams = { "kitID", "pageID", "id", "os-name", "jvm-name", "jvm-version", "platform",
        "tc-version", "tc-product", "source" };
    Map paramMap = request.getParameterMap();
    for (String expectedParam : expectedParams) {
      if (paramMap.get(expectedParam) == null) {
        response.getWriter().println("Missing parameter '" + expectedParam + "'");
        return;
      }
    }
    if (paramMap.size() != expectedParams.length) {
      response.getWriter().println("Parameter count doesn't match expected count='" + expectedParams.length + "'");
      return;
    }
    response.getWriter().println("OK");
  }
}
