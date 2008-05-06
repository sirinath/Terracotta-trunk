/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.tcm;

import com.tc.async.api.Sink;
import com.tc.util.TCTimeoutException;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;

public interface NetworkListener {

  public void start(Set initialConnectionIDs) throws IOException;

  public void stop(long timeout) throws TCTimeoutException;

  public void routeMessageType(TCMessageType messageType, TCMessageSink sink);

  public void routeMessageType(TCMessageType messageType, Sink destSink, Sink hydrateSink);

  public ChannelManager getChannelManager();

  public void addClassMapping(TCMessageType type, Class msgClass);

  public InetAddress getBindAddress();

  public int getBindPort();

}
