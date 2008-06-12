/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.net.protocol.delivery;

import com.tc.async.api.Sink;

public interface OnceAndOnlyOnceProtocolNetworkLayerFactory {
  public OnceAndOnlyOnceProtocolNetworkLayer createNewClientInstance(Sink workSink, int sendQueueCap);
  public OnceAndOnlyOnceProtocolNetworkLayer createNewServerInstance(Sink workSink, int sendQueueCap);
}
