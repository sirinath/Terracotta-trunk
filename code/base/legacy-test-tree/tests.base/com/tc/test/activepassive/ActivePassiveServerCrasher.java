/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.activepassive;

import com.tctest.TestState;

public class ActivePassiveServerCrasher implements Runnable {
  private static boolean                   DEBUG      = false;
  private final ActivePassiveServerManager serverManger;
  private final long                       serverCrashWaitTimeInSec;
  private final int                        maxCrashCount;

  private int                              crashCount = 0;
  private final TestState                  testState;

  public ActivePassiveServerCrasher(ActivePassiveServerManager serverManager, long serverCrashWaitTimeInSec,
                                    int maxCrashCount, TestState testState) {
    this.serverManger = serverManager;
    this.serverCrashWaitTimeInSec = serverCrashWaitTimeInSec;
    this.maxCrashCount = maxCrashCount;
    this.testState = testState;
  }

  public void run() {
    while (true) {
      try {
        Thread.sleep(serverCrashWaitTimeInSec * 1000);
      } catch (InterruptedException e1) {
        serverManger.storeErrors(e1);
      }

      synchronized (testState) {
        if (testState.isRunning() && (maxCrashCount - crashCount) > 0 && serverManger.getErrors().isEmpty()) {
          try {
            debugPrintln("***** ActivePassiveServerCrasher:  about to crash server  threadID=["
                         + Thread.currentThread().getName() + "]");
            serverManger.crashServer();

            debugPrintln("***** ActivePassiveServerCrasher:  about to restart crashed server threadID=["
                         + Thread.currentThread().getName() + "]");
            serverManger.restartLastCrashedServer();

            crashCount++;
          } catch (Exception e) {
            debugPrintln("***** ActivePassiveServerCrasher:  error occured while crashing/restarting server  threadID=["
                         + Thread.currentThread().getName() + "]");

            e.printStackTrace();

            serverManger.storeErrors(e);
          }
        } else {
          debugPrintln("***** ActivePassiveServerCrasher is done: testStateRunning[" + testState.isRunning()
                       + "] errors[" + serverManger.getErrors().size() + "] crashCount[" + crashCount + "]");
          break;
        }
      }
    }
  }

  public void debugPrintln(String s) {
    if (DEBUG) {
      System.err.println(s);
    }
  }
}
