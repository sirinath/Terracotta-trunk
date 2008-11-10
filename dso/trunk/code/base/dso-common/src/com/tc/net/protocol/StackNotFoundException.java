/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.net.protocol;

import com.tc.net.TCSocketAddress;
import com.tc.net.protocol.transport.ConnectionID;

/**
 * Thrown by Stack providers when a requested network stack cannot be located
 */
public class StackNotFoundException extends Exception {

  public StackNotFoundException(ConnectionID connectionId, TCSocketAddress socketAddress) {
    super(connectionId + " not found. Connection is being requested from "+ socketAddress);
  }
}