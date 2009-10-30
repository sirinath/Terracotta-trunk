/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.async.impl;

import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.EventContext;
import com.tc.async.api.EventHandler;
import com.tc.async.api.EventHandlerException;
import com.tc.async.api.Sink;
import com.tc.async.api.Source;
import com.tc.async.api.SpecializedEventContext;
import com.tc.async.api.Stage;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLoggerProvider;
import com.tc.util.concurrent.QueueFactory;

/**
 * The SEDA Stage
 */
public class StageImpl implements Stage {
  private static final long    pollTime = 3000; // This is the poor man's solution for stage
  private final String         name;
  private final EventHandler   handler;
  private final StageQueueImpl stageQueue;
  private final WorkerThread[] threads;
  private final ThreadGroup    group;
  private final TCLogger       logger;

  /**
   * The Constructor.
   * 
   * @param loggerProvider : logger
   * @param name : The stage name
   * @param handler : Event handler for this stage
   * @param threadCount : Number of threads working on this stage
   * @param threadsToQueueRatio : The ratio determines the number of queues internally used and the number of threads
   *        per each queue. Ideally you would want this to be same as threadCount, in which case there is only 1 queue
   *        used internally and all thread are working on the same queue (which doesn't guarantee order in processing)
   *        or set it to 1 where each thread gets its own queue but the (multithreaded) event contexts are distributed
   *        based on the key they return. 
   * @param group : The thread group to be used
   * @param queueFactory : Factory used to create the queues
   * @param queueSize : Max queue Size allowed
   */
  public StageImpl(TCLoggerProvider loggerProvider, String name, EventHandler handler, int threadCount,
                   int threadsToQueueRatio, ThreadGroup group, QueueFactory queueFactory, int queueSize) {
    this.logger = loggerProvider.getLogger(Stage.class.getName() + ": " + name);
    this.name = name;
    this.handler = handler;
    this.threads = new WorkerThread[threadCount];
    if (threadsToQueueRatio > threadCount) {
      logger.warn("Thread to Queue Ratio " + threadsToQueueRatio + " > Worker Threads " + threadCount);
    }
    this.stageQueue = new StageQueueImpl(threadCount, threadsToQueueRatio, queueFactory, loggerProvider, name,
                                         queueSize);
    this.group = group;
  }

  public void destroy() {
    stopThreads();
  }

  public void start(ConfigurationContext context) {
    handler.initializeContext(context);
    startThreads();
  }

  public Sink getSink() {
    return stageQueue;
  }

  public String getName() {
    return name;
  }

  private synchronized void startThreads() {
    for (int i = 0; i < threads.length; i++) {
      String threadName = "WorkerThread(" + name + ", " + i;
      if (threads.length > 1) {
        threadName = threadName + ", " + this.stageQueue.getSource(i).getSourceName() + ")";
      } else {
        threadName = threadName + ")";
      }
      threads[i] = new WorkerThread(threadName, this.stageQueue.getSource(i), handler, group);
      threads[i].start();
    }
  }

  private void stopThreads() {
    for (int i = 0; i < threads.length; i++) {
      threads[i].shutdown();
    }
  }

  public String toString() {
    return "StageImpl(" + name + ")";
  }

  private static class WorkerThread extends Thread {
    private final Source       source;
    private final EventHandler handler;
    private volatile boolean   shutdownRequested = false;

    public WorkerThread(String name, Source source, EventHandler handler, ThreadGroup group) {
      super(group, name);
      setDaemon(true);
      this.source = source;
      this.handler = handler;
    }

    public void shutdown() {
      this.shutdownRequested = true;
    }

    private boolean shutdownRequested() {
      return this.shutdownRequested;
    }

    public void run() {
      while (!shutdownRequested()) {
        EventContext ctxt;
        try {
          ctxt = source.poll(pollTime);
          if (ctxt != null) {
            if (ctxt instanceof SpecializedEventContext) {
              ((SpecializedEventContext) ctxt).execute();
            } else {
              handler.handleEvent(ctxt);
            }
          }
        } catch (InterruptedException ie) {
          if (shutdownRequested()) { return; }
          throw new TCRuntimeException(ie);
        } catch (EventHandlerException ie) {
          if (shutdownRequested()) return;
          throw new TCRuntimeException(ie);
        } finally {
          // Aggressively null out the reference before going around the loop again. If you don't do this, the reference
          // to the context will exist until another context comes in. This can potentially keep many objects in memory
          // longer than necessary
          ctxt = null;
        }
      }
    }
  }

}