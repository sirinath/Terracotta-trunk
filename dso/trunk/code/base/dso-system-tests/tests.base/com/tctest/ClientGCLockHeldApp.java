/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import EDU.oswego.cs.dl.util.concurrent.CyclicBarrier;
import EDU.oswego.cs.dl.util.concurrent.SynchronizedInt;

import com.tc.object.config.ConfigVisitor;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.object.config.spec.CyclicBarrierSpec;
import com.tc.object.config.spec.SynchronizedIntSpec;
import com.tc.properties.TCPropertiesImpl;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tctest.runner.AbstractTransparentApp;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ClientGCLockHeldApp extends AbstractTransparentApp {

  private static final int MINUTES_TEST_RUN = 10;

  final List               lockList         = new ArrayList();
  final List               lockObj          = new ArrayList();
  CyclicBarrier            barrier;

  public ClientGCLockHeldApp(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
    super(appId, cfg, listenerProvider);
  }

  public static void visitL1DSOConfig(ConfigVisitor visitor, DSOClientConfigHelper config) {
    String testClass = ClientGCLockHeldApp.class.getName();
    TransparencyClassSpec spec = config.getOrCreateSpec(testClass);
    spec.addRoot("lockList", "lockList");
    spec.addRoot("lockObj", "lockObj");
    spec.addRoot("barrier", "barrier");
    String methodExpression = "* " + testClass + "*.*(..)";
    config.addWriteAutolock(methodExpression);
    new SynchronizedIntSpec().visit(visitor, config);
    new CyclicBarrierSpec().visit(visitor, config);

  }

  public void run() {
    setCyclicBarrier();
    try {
      barrier.barrier();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // start a thread that holds a lock forever.
    RunForeverThread thread = new RunForeverThread();
    thread.start();

    Stopwatch stopwatch = new Stopwatch().start();

    Runtime runtime = Runtime.getRuntime();
    long maxMemory = runtime.maxMemory();

    System.out.println("maxMemory: " + maxMemory);

    int locksSize = (int) (maxMemory / 15000);

    int stripedCount = Integer.valueOf(TCPropertiesImpl.getProperties().getProperty("l1.lockmanager.striped.count"))
        .intValue();

    System.out.println("stripedCount = " + stripedCount);

    if (stripedCount > 0) {
      locksSize = locksSize / stripedCount;
    }
    
    System.out.println("locksSize = " + locksSize);
    while (stopwatch.getElapsedTime() < (1000 * 60 * MINUTES_TEST_RUN)) {

      for (int i = 0; i < locksSize; i++) {

        SynchronizedInt counter = new SynchronizedInt(0);
        synchronized (lockList) {
          lockList.add(counter);
        }
        counter.increment();
      }
      // now sleep for awhile, so locks created can be GCed, if there
      // is a bug, then it won't be GCed and eventually OOME
      try {
        Thread.sleep(61 * 1000);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  private void setCyclicBarrier() {
    int participationCount = getParticipantCount();
    log("Participation Count = " + participationCount);
    barrier = new CyclicBarrier(participationCount);
  }

  static DateFormat formatter = new SimpleDateFormat("hh:mm:ss,S");

  private static void log(String message) {
    System.err.println(Thread.currentThread().getName() + " :: "
                       + formatter.format(new Date(System.currentTimeMillis())) + " : " + message);
  }

  public void holdLock() {
    synchronized (lockObj) {
      try {
        Thread.currentThread().join();
        log("should not reach here, this thread should hold the lock forever");
        notifyError("should not reach here, this thread should hold the lock forever");
      } catch (InterruptedException e) {
        log("error occurred holding lock");
        notifyError(e);
      }
    }
  }

  private class RunForeverThread extends Thread {

    public void run() {
      holdLock();
    }

  }

  private static class Stopwatch {
    private long    startTime = -1;
    private long    stopTime  = -1;
    private boolean running   = false;

    public Stopwatch start() {
      startTime = System.currentTimeMillis();
      running = true;
      return this;
    }

    public Stopwatch stop() {
      stopTime = System.currentTimeMillis();
      running = false;
      return this;
    }

    public long getElapsedTime() {
      if (startTime == -1) { return 0; }
      if (running) {
        return System.currentTimeMillis() - startTime;
      } else {
        return stopTime - startTime;
      }
    }

    public Stopwatch reset() {
      startTime = -1;
      stopTime = -1;
      running = false;
      return this;
    }

  }

}
