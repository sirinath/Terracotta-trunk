/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.core;

import com.tc.util.Assert;

public class ConnectionInfo implements java.io.Serializable {

  public static final ConnectionInfo[] EMPTY_ARRAY = new ConnectionInfo[0];
  private final String                 hostname;
  private final int                    port;
  private final int                    groupId;

  public ConnectionInfo(String hostname, int port) {
    this(hostname, port, -1);
  }

  public ConnectionInfo(String hostname, int port, int groupId) {
    Assert.assertNotNull(hostname);
    this.hostname = hostname;
    this.port = port;
    this.groupId = groupId;
  }

  public String getHostname() {
    return hostname;
  }

  public int getPort() {
    return port;
  }
  
  public int getGroupId() {
    return groupId;
  }

  public boolean equals(Object o) {
    if (o == this) return true;
    if (o instanceof ConnectionInfo) {
      ConnectionInfo other = (ConnectionInfo) o;
      return this.hostname.equals(other.getHostname()) && this.port == other.getPort();
    }
    return false;
  }

  public int hashCode() {
    return toString().hashCode();
  }

  private String s;

  public String toString() {
    return (s == null ? (s = hostname + ":" + port) : s);
  }
}
