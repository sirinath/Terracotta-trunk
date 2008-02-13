/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import com.tc.net.core.event.TCConnectionEventListener;

/**
 * Helps HealthChecker in doing extra checks to monitor peer node's health. Here, a socket connect is attempted to some
 * of the peer node's listening port.
 * 
 * @author Manoj
 */
public interface HealthCheckerSocketConnect extends TCConnectionEventListener {

  public boolean start();

  /* Once in a probe interval, the healthchecker queries to get the connect status if wanted */
  public boolean porobeConnectStatus();

}
