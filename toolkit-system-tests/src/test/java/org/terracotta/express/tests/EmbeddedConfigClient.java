/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.express.tests;

import org.terracotta.express.tests.base.ClientBase;
import org.terracotta.toolkit.Toolkit;

import com.terracotta.toolkit.client.TerracottaClientConfig;
import com.terracotta.toolkit.client.TerracottaClientConfigParams;
import com.terracotta.toolkit.express.TerracottaInternalClient;
import com.terracotta.toolkit.express.TerracottaInternalClientStaticFactory;

public class EmbeddedConfigClient extends ClientBase {

  public EmbeddedConfigClient(String[] args) {
    super(args);
  }

  public static void main(String[] args) {
    new EmbeddedConfigClient(args).run();
  }

  @Override
  protected void test(Toolkit toolkit) throws Throwable {
    String tcConfig = "<tc:tc-config xmlns:tc=\"http://www.terracotta.org/config\">\n" +
      "<servers>\n" +
        "<mirror-group group-name=\"testGroup0\">\n" +
          "<server host=\"localhost\" name=\"testserver0\">\n" +
            "<tsa-port>TSA_PORT</tsa-port>\n" +
          "</server>\n" +
        "</mirror-group>\n" +
      "</servers>\n" +
    "</tc:tc-config>";

    String tsaPort = getTerracottaUrl().split(":")[1];
    tcConfig = tcConfig.replace("TSA_PORT", tsaPort);

    TerracottaClientConfig config = new TerracottaClientConfigParams().tcConfigSnippetOrUrl(tcConfig).isUrl(false)
        .newTerracottaClientConfig();
    TerracottaInternalClient client1 = TerracottaInternalClientStaticFactory
        .getOrCreateTerracottaInternalClient(config);
    client1.init();

    // no assertion here, client1 should start correctly without problem

    client1.shutdown();
  }
}
