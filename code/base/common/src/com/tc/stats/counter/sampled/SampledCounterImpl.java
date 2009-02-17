/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.stats.counter.sampled;

import com.tc.stats.LossyStack;
import com.tc.stats.counter.CounterImpl;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.TimerTask;

/**
 * A counter that keeps sampled values
 */
public class SampledCounterImpl extends CounterImpl implements SampledCounter {
  protected final LossyStack        history;
  protected final boolean           resetOnSample;
  private final TimerTask           samplerTask;
  private final long                intervalMillis;
  protected TimeStampedCounterValue min;
  protected TimeStampedCounterValue max;
  protected Double                  average = null;

  public SampledCounterImpl(SampledCounterConfig config) {
    super(config.getInitialValue());

    this.intervalMillis = config.getIntervalSecs() * 1000;
    this.history = new LossyStack(config.getHistorySize());
    this.resetOnSample = config.isResetOnSample();

    this.samplerTask = new TimerTask() {
      public void run() {
        recordSample();
      }
    };

    recordSample();
  }

  public TimeStampedCounterValue getMostRecentSample() {
    return (TimeStampedCounterValue) this.history.peek();
  }

  public TimeStampedCounterValue[] getAllSampleValues() {
    return (TimeStampedCounterValue[]) this.history.toArray(new TimeStampedCounterValue[this.history.depth()]);
  }

  public void shutdown() {
    if (samplerTask != null) {
      samplerTask.cancel();
    }
  }

  public TimerTask getTimerTask() {
    return this.samplerTask;
  }

  public long getIntervalMillis() {
    return intervalMillis;
  }

  synchronized void recordSample() {
    final long sample;
    if (resetOnSample) {
      sample = getAndReset();
    } else {
      sample = getValue();
    }

    final long now = System.currentTimeMillis();
    TimeStampedCounterValue timedSample = new TimeStampedCounterValue(now, sample);

    if ((min == null) || (sample < min.getCounterValue())) {
      min = timedSample;
    }

    if ((max == null) || (sample > max.getCounterValue())) {
      max = timedSample;
    }

    average = null; // force average to be computed again
    history.push(timedSample);
  }

  public synchronized TimeStampedCounterValue getMin() {
    return this.min;
  }

  public synchronized TimeStampedCounterValue getMax() {
    return this.max;
  }

  public synchronized long getAndReset() {
    long prevVal = getValue();
    setValue(0L);
    return prevVal;
  }

  public synchronized double getAverage() {
    if (average == null) {
      average = new Double(computeAverage());
    }
    return this.average.doubleValue();
  }

  private double computeAverage() {
    TimeStampedCounterValue[] all = getAllSampleValues();

    if (all.length == 0) { return 0D; }

    BigInteger total = BigInteger.ZERO;
    for (int i = 0, n = all.length; i < n; i++) {
      final long sample = all[i].getCounterValue();
      total = total.add(new BigInteger(String.valueOf(sample)));
    }

    return new BigDecimal(total).divide(new BigDecimal(all.length), BigDecimal.ROUND_HALF_UP).doubleValue();
  }
}
