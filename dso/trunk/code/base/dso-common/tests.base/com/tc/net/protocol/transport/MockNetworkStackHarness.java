/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import com.tc.net.core.TCConnection;
import com.tc.net.protocol.IllegalReconnectException;
import com.tc.net.protocol.NetworkStackHarness;

public class MockNetworkStackHarness implements NetworkStackHarness {
  public boolean wasAttachNewConnectionCalled = false;
  public boolean wasFinalizeStackCalled       = false;

  public MessageTransport attachNewConnection(TCConnection connection) throws IllegalReconnectException {
    this.wasAttachNewConnectionCalled = true;
    return null;
  }

  public void finalizeStack() {
    this.wasFinalizeStackCalled = true;
  }

}