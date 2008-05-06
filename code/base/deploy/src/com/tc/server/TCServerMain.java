/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.server;

import com.tc.config.schema.setup.FatalIllegalConfigurationChangeHandler;
import com.tc.config.schema.setup.StandardTVSConfigurationSetupManagerFactory;
import com.tc.config.schema.setup.TVSConfigurationSetupManagerFactory;
import com.tc.lang.TCThreadGroup;
import com.tc.lang.ThrowableHandler;
import com.tc.logging.TCLogging;

public class TCServerMain {

  public static void main(final String[] args) {
    ThrowableHandler throwableHandler = new ThrowableHandler(TCLogging.getLogger(TCServerMain.class));

    try {
      TCThreadGroup threadGroup = new TCThreadGroup(throwableHandler);

      TVSConfigurationSetupManagerFactory factory = new StandardTVSConfigurationSetupManagerFactory(
                                                                                                    args,
                                                                                                    true,
                                                                                                    new FatalIllegalConfigurationChangeHandler());
      AbstractServerFactory serverFactory = AbstractServerFactory.getFactory();
      TCServer server = serverFactory.createServer(factory.createL2TVSConfigurationSetupManager(null), threadGroup);
      server.start();

    } catch (Throwable t) {
      throwableHandler.handleThrowable(Thread.currentThread(), t);
    }
  }
}
