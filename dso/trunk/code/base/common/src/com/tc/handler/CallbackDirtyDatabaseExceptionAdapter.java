/*
 * All content copyright (c) 2003-2009 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.handler;

import com.tc.logging.CallbackOnExitState;
import com.tc.logging.TCLogger;
import com.tc.object.persistence.api.PersistentMapStore;

public class CallbackDirtyDatabaseExceptionAdapter extends CallbackDirtyDatabaseCleanUpAdapter {

  private final TCLogger consoleLogger;
  private String         consoleMessage = "This standby Terracotta server instance had to restart to "
                                          + "automatically wipe its database and rejoin the cluster.";

  public CallbackDirtyDatabaseExceptionAdapter(TCLogger logger, TCLogger consoleLogger,
                                               PersistentMapStore clusterStateStore) {
    super(logger, clusterStateStore);
    this.consoleLogger = consoleLogger;
  }

  @Override
  public void callbackOnExit(CallbackOnExitState state) {
    super.callbackOnExit(state);
    consoleLogger.error(consoleMessage + "\n");
  }

}
