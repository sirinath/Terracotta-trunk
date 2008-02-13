/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

/**
 * Default Health Chcker config passed to the Communications Manager
 * 
 * @author Manoj
 */
public class DisabledHealthCheckerConfigImpl implements HealthCheckerConfig {

  public boolean isHealthCheckerEnabled() {
    return false;
  }

  public int getPingIdleTime() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public int getPingInterval() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public int getPingProbes() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public String getHealthCheckerName() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public boolean isSocketConnectOnPingFail() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public int getMaxSocketConnectCount() {
    throw new AssertionError("Disabled HealthChecker");
  }

}
