/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.spring.integrationtests.tests;

import com.tc.test.server.appserver.deployment.Server;
import com.tctest.spring.bean.ISingleton;

import junit.framework.Assert;

public class SingletonStateUtil {

  public static void assertSingletonShared(Server server1, Server server2, String remoteServiceName) throws Exception {
    ISingleton singleton1 = (ISingleton) server1.getProxy(ISingleton.class, remoteServiceName);
    ISingleton singleton2 = (ISingleton) server2.getProxy(ISingleton.class, remoteServiceName);
    Assert.assertEquals(singleton1.getCounter(), singleton2.getCounter());
    singleton1.incrementCounter();
    Assert.assertEquals("Should be shared", singleton1.getCounter(), singleton2.getCounter());
    singleton2.incrementCounter();
    Assert.assertEquals("Should be shared", singleton2.getCounter(), singleton1.getCounter());
  }

}
