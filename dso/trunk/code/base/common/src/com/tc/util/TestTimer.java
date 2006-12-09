/**
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.util;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimerTask;

public class TestTimer implements TCTimer {

  public List scheduleCalls       = new ArrayList();
  public List cancelCalls = new ArrayList();

  public void cancel() {
    cancelCalls.add(new Object());
  }

  public void schedule(TimerTask task, long delay) {
    scheduleCalls.add(new ScheduleCallContext(task, new Long(delay), null, null));
  }

  public void schedule(TimerTask task, Date time) {
    return;

  }

  public void schedule(TimerTask task, long delay, long period) {
    return;

  }

  public void schedule(TimerTask task, Date firstTime, long period) {
    return;

  }

  public void scheduleAtFixedRate(TimerTask task, long delay, long period) {
    return;

  }

  public void scheduleAtFixedRate(TimerTask task, Date firstTime, long period) {
    return;

  }

  public static final class ScheduleCallContext {
    public final TimerTask task;
    public final Long      delay;
    public final Date      time;
    public final Long      period;

    private ScheduleCallContext(TimerTask task, Long delay, Date time, Long period) {
      this.task = task;
      this.delay = delay;
      this.time = time;
      this.period = period;
    }
  }

}
