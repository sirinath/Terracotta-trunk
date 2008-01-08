/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Collection;

public class ServerManagerUtil {

  protected static Log logger = LogFactory.getLog(ServerManagerUtil.class);

  private static ServerManager start(Class testClass, boolean withPersistentStore, Collection extraJvmArgs)
      throws Exception {
    ServerManager existingServerManager = getExistingServerManager();
    if (existingServerManager != null) {
      logger.debug("Using existing ServerManager");
      return existingServerManager;
    }
    logger.debug("Creating server manager");
    ServerManager serverManager = new ServerManager(testClass, extraJvmArgs);
    serverManager.start(withPersistentStore);
    return serverManager;
  }

  public static void stop(ServerManager serverManager) {
    ServerManager existingServerManager = getExistingServerManager();
    if (existingServerManager != null) {
      logger.debug("Not stopping existing ServerManager");
      return;
    }
    logger.debug("Stopping ServerManager");
    serverManager.stop();
  }

  private static ThreadLocal serverManagerHolder = new ThreadLocal();

  private static ServerManager getExistingServerManager() {
    return (ServerManager) serverManagerHolder.get();
  }

  public static ServerManager startAndBind(Class testClass, boolean withPersistentStore, Collection extraJvmArgs)
      throws Exception {
    ServerManager sm = start(testClass, withPersistentStore, extraJvmArgs);
    serverManagerHolder.set(sm);
    return sm;
  }

  public static void stopAndRelease(ServerManager sm) {
    serverManagerHolder.set(null);
    stop(sm);
  }

  public static void stopAllWebServers(ServerManager serverManager) {
    getExistingServerManager().stopAllWebServers();
  }

}
