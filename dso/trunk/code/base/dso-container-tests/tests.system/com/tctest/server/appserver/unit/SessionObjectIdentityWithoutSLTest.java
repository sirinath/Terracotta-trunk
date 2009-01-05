/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.server.appserver.unit;

import junit.framework.Test;

public class SessionObjectIdentityWithoutSLTest extends SessionObjectIdentityTest {

  public static Test suite() {
    return new SessionObjectIdentityWithoutSLTestSetup();
  }

  private static class SessionObjectIdentityWithoutSLTestSetup extends SessionObjectIdentityTestSetup {

    public SessionObjectIdentityWithoutSLTestSetup() {
      super(SessionObjectIdentityWithoutSLTest.class, CONTEXT);
    }

    @Override
    public boolean isSessionLockingTrue() {
      return false;
    }

  }
}
