/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.admin;

import java.util.prefs.Preferences;

public class ServerThreadDumpsPanel extends AbstractThreadDumpsPanel {
  private ServerThreadDumpsNode m_serverThreadDumpsNode;

  public ServerThreadDumpsPanel(ServerThreadDumpsNode serverThreadDumpsNode) {
    super();
    m_serverThreadDumpsNode = serverThreadDumpsNode;
  }

  protected String getThreadDumpText() throws Exception {
    long requestMillis = System.currentTimeMillis();
    ConnectionContext cc = m_serverThreadDumpsNode.getConnectionContext();
    return ServerHelper.getHelper().takeThreadDump(cc, requestMillis);
  }

  protected Preferences getPreferences() {
    AdminClientContext acc = AdminClient.getContext();
    return acc.prefs.node("ServerThreadDumpsPanel");
  }

  public void tearDown() {
    super.tearDown();
    m_serverThreadDumpsNode = null;
  }
}
