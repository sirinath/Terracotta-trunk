/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.admin;

import com.tc.admin.common.XObjectTableModel;

public class ClusterMemberTableModel extends XObjectTableModel {
  private static final String[] CLUSTER_MEMBERS_FIELDS  = { "Name", "Hostname", "JMXPortNumber" };
  private static final String[] CLUSTER_MEMBERS_HEADERS = { "Name", "Host", "Admin Port" };

  public ClusterMemberTableModel() {
    super(ServerConnectionManager.class, CLUSTER_MEMBERS_FIELDS, CLUSTER_MEMBERS_HEADERS);
  }

  public void addClusterMember(ServerConnectionManager scm) {
    add(scm);
  }

  public ServerConnectionManager getClusterMemberAt(int row) {
    return (ServerConnectionManager) getObjectAt(row);
  }

  public void clear() {
    int count = getRowCount();
    for (int i = 0; i < count; i++) {
      getClusterMemberAt(i).tearDown();
    }
    super.clear();
  }

  public void tearDown() {
    clear();
  }
}
