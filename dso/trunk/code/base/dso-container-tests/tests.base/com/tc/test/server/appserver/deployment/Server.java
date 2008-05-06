/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.test.server.appserver.deployment;


import java.util.Map;

import javax.management.MBeanServerConnection;

public interface Server extends Stoppable {
  public Server restart() throws Exception;
  public Object getProxy(Class serviceType, String url) throws Exception;
  public Object getProxy(Class serviceType, String url, Map initialContext) throws Exception;
  public MBeanServerConnection getMBeanServerConnection() throws Exception;
}
