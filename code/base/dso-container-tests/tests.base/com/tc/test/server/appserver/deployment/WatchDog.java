/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.tc.util.runtime.ThreadDump;

import java.util.Timer;
import java.util.TimerTask;

public class WatchDog {
  protected Log        logger = LogFactory.getLog(getClass());

  private final Thread threadToWatch;
  private final Timer  timer;
  private TimerTask    timerTask;
  private TimerTask    dumpTask;

  private final int    timeoutInSecs;

  public WatchDog(int timeOutInSecs) {
    timeoutInSecs = timeOutInSecs;
    this.threadToWatch = Thread.currentThread();
    this.timer = new Timer();
  }

  public void startWatching() {
    logger.debug("Watching thread");
    timerTask = new TimerTask() {
      @Override
      public void run() {
        logger.error("Thread timeout..interrupting");
        threadToWatch.interrupt();

      }
    };

    dumpTask = new TimerTask() {
      @Override
      public void run() {
        ThreadDump.dumpAllJavaProceses();
      }
    };

    timer.schedule(timerTask, timeoutInSecs * 1000);
    timer.schedule(dumpTask, (timeoutInSecs - 45) * 1000);
  }

  public void stopWatching() {
    logger.debug("watching cancelled..");
    timerTask.cancel();
    dumpTask.cancel();
    timer.cancel();
  }

}
