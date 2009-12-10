/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.dso;

import static com.tc.admin.model.IClient.POLLED_ATTR_PENDING_TRANSACTIONS_COUNT;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_CPU_USAGE;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_MAX_MEMORY;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_OBJECT_FAULT_RATE;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_OBJECT_FLUSH_RATE;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_TRANSACTION_RATE;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_USED_MEMORY;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.TimeSeries;

import com.tc.admin.common.ApplicationContext;
import com.tc.admin.common.BasicWorker;
import com.tc.admin.common.XContainer;
import com.tc.admin.model.IClient;
import com.tc.admin.model.IClusterModel;
import com.tc.admin.model.IClusterModelElement;
import com.tc.admin.model.PolledAttributesResult;
import com.tc.statistics.StatisticData;

import java.awt.GridLayout;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

public class ClientRuntimeStatsPanel extends BaseRuntimeStatsPanel implements PropertyChangeListener {
  private IClient                  client;

  private TimeSeries               memoryMaxSeries;
  private TimeSeries               memoryUsedSeries;

  private ChartPanel               cpuPanel;
  private TimeSeries[]             cpuTimeSeries;

  private TimeSeries               flushRateSeries;
  private TimeSeries               faultRateSeries;
  private TimeSeries               txnRateSeries;
  private TimeSeries               pendingTxnsSeries;

  private static final Set<String> POLLED_ATTRIBUTE_SET = new HashSet(Arrays
                                                            .asList(POLLED_ATTR_CPU_USAGE, POLLED_ATTR_USED_MEMORY,
                                                                    POLLED_ATTR_MAX_MEMORY,
                                                                    POLLED_ATTR_OBJECT_FLUSH_RATE,
                                                                    POLLED_ATTR_OBJECT_FAULT_RATE,
                                                                    POLLED_ATTR_TRANSACTION_RATE,
                                                                    POLLED_ATTR_PENDING_TRANSACTIONS_COUNT));

  public ClientRuntimeStatsPanel(ApplicationContext appContext, IClient client) {
    super(appContext);
    this.client = client;
    setup(chartsPanel);
    setName(client.toString());
    client.addPropertyChangeListener(this);
  }

  private synchronized IClient getClient() {
    return client;
  }

  public void propertyChange(PropertyChangeEvent evt) {
    String prop = evt.getPropertyName();
    if (prop.equals(IClusterModelElement.PROP_READY)) {
      Boolean isReady = (Boolean) evt.getNewValue();
      if (isReady.booleanValue() && isMonitoringRuntimeStats()) {
        addPolledAttributeListener();
      }
    }
  }

  private void addPolledAttributeListener() {
    IClient theClient = getClient();
    if (theClient != null) {
      theClient.addPolledAttributeListener(POLLED_ATTRIBUTE_SET, this);
    }
  }

  private void removePolledAttributeListener() {
    IClient theClient = getClient();
    if (theClient != null) {
      theClient.removePolledAttributeListener(POLLED_ATTRIBUTE_SET, this);
    }
  }

  @Override
  public void startMonitoringRuntimeStats() {
    IClient theClient = getClient();
    if (theClient != null && theClient.isReady()) {
      addPolledAttributeListener();
    }
    super.startMonitoringRuntimeStats();
  }

  @Override
  public void stopMonitoringRuntimeStats() {
    removePolledAttributeListener();
    super.stopMonitoringRuntimeStats();
  }

  @Override
  public void attributesPolled(PolledAttributesResult result) {
    IClient theClient = getClient();
    if (theClient != null) {
      handleDSOStats(result);
      handleSysStats(result);
    }
  }

  // TODO: this is broken wrt AA as only the active coordinator will be used.

  private synchronized void handleDSOStats(PolledAttributesResult result) {
    IClusterModel theClusterModel = client.getClusterModel();
    if (theClusterModel != null) {
      IClient theClient = getClient();
      if (theClient != null) {
        tmpDate.setTime(System.currentTimeMillis());

        long flush = 0;
        long fault = 0;
        long txn = 0;
        long pendingTxn = 0;
        Number n;

        n = (Number) result.getPolledAttribute(theClient, POLLED_ATTR_OBJECT_FLUSH_RATE);
        if (n != null) {
          if (flush >= 0) flush += n.longValue();
        } else {
          flush = -1;
        }
        n = (Number) result.getPolledAttribute(theClient, POLLED_ATTR_OBJECT_FAULT_RATE);
        if (n != null) {
          if (fault >= 0) fault += n.longValue();
        } else {
          fault = -1;
        }
        n = (Number) result.getPolledAttribute(theClient, POLLED_ATTR_PENDING_TRANSACTIONS_COUNT);
        if (n != null) {
          if (pendingTxn >= 0) pendingTxn += n.longValue();
        } else {
          pendingTxn = -1;
        }
        n = (Number) result.getPolledAttribute(theClient, POLLED_ATTR_TRANSACTION_RATE);
        if (n != null) {
          if (txn >= 0) txn += n.longValue();
        } else {
          txn = -1;
        }

        final long theFlushRate = flush;
        final long theFaultRate = fault;
        final long theTxnRate = txn;
        final long thePendingTxnRate = pendingTxn;
        SwingUtilities.invokeLater(new Runnable() {
          public void run() {
            updateAllDSOSeries(theFlushRate, theFaultRate, theTxnRate, thePendingTxnRate);
          }
        });
      }
    }
  }

  private void updateAllDSOSeries(long flush, long fault, long txn, long pendingTxn) {
    if (flush != -1) {
      updateSeries(flushRateSeries, Long.valueOf(flush));
    }
    if (fault != -1) {
      updateSeries(faultRateSeries, Long.valueOf(fault));
    }
    if (txn != -1) {
      updateSeries(txnRateSeries, Long.valueOf(txn));
    }
    if (pendingTxn != -1) {
      updateSeries(pendingTxnsSeries, Long.valueOf(pendingTxn));
    }
  }

  private synchronized void handleSysStats(final PolledAttributesResult result) {
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        IClient theClient = getClient();
        if (theClient != null) {
          updateSeries(memoryMaxSeries, (Number) result.getPolledAttribute(theClient, POLLED_ATTR_MAX_MEMORY));
          updateSeries(memoryUsedSeries, (Number) result.getPolledAttribute(theClient, POLLED_ATTR_USED_MEMORY));

          if (cpuTimeSeries != null) {
            StatisticData[] cpuUsageData = (StatisticData[]) result
                .getPolledAttribute(theClient, POLLED_ATTR_CPU_USAGE);
            handleCpuUsage(cpuTimeSeries, cpuUsageData);
          }
        }
      }
    });
  }

  @Override
  protected synchronized void setup(XContainer chartsPanel) {
    chartsPanel.setLayout(new GridLayout(0, 2));
    setupMemoryPanel(chartsPanel);
    setupCpuPanel(chartsPanel);
    setupTxnRatePanel(chartsPanel);
    setupPendingTxnsPanel(chartsPanel);
    setupFlushRatePanel(chartsPanel);
    setupFaultRatePanel(chartsPanel);
  }

  private void setupFlushRatePanel(XContainer parent) {
    flushRateSeries = createTimeSeries("");
    ChartPanel flushRatePanel = createChartPanel(createChart(flushRateSeries, false));
    parent.add(flushRatePanel);
    flushRatePanel.setPreferredSize(fDefaultGraphSize);
    flushRatePanel.setBorder(new TitledBorder(appContext.getString("client.stats.flush.rate")));
    flushRatePanel.setToolTipText(appContext.getString("client.stats.flush.rate.tip"));
  }

  private void setupFaultRatePanel(XContainer parent) {
    faultRateSeries = createTimeSeries("");
    ChartPanel faultRatePanel = createChartPanel(createChart(faultRateSeries, false));
    parent.add(faultRatePanel);
    faultRatePanel.setPreferredSize(fDefaultGraphSize);
    faultRatePanel.setBorder(new TitledBorder(appContext.getString("client.stats.fault.rate")));
    faultRatePanel.setToolTipText(appContext.getString("client.stats.fault.rate.tip"));
  }

  private void setupTxnRatePanel(XContainer parent) {
    txnRateSeries = createTimeSeries("");
    ChartPanel txnRatePanel = createChartPanel(createChart(txnRateSeries, false));
    parent.add(txnRatePanel);
    txnRatePanel.setPreferredSize(fDefaultGraphSize);
    txnRatePanel.setBorder(new TitledBorder(appContext.getString("client.stats.transaction.rate")));
    txnRatePanel.setToolTipText(appContext.getString("client.stats.transaction.rate.tip"));
  }

  private void setupPendingTxnsPanel(XContainer parent) {
    pendingTxnsSeries = createTimeSeries("");
    ChartPanel pendingTxnsPanel = createChartPanel(createChart(pendingTxnsSeries, false));
    parent.add(pendingTxnsPanel);
    pendingTxnsPanel.setPreferredSize(fDefaultGraphSize);
    pendingTxnsPanel.setBorder(new TitledBorder(appContext.getString("client.stats.pending.transactions")));
    pendingTxnsPanel.setToolTipText(appContext.getString("client.stats.pending.transactions.tip"));
  }

  private void setupMemoryPanel(XContainer parent) {
    memoryMaxSeries = createTimeSeries(appContext.getString("heap.usage.max"));
    memoryUsedSeries = createTimeSeries(appContext.getString("heap.usage.used"));
    JFreeChart memoryChart = createChart(new TimeSeries[] { memoryMaxSeries, memoryUsedSeries });
    XYPlot plot = (XYPlot) memoryChart.getPlot();
    NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
    numberAxis.setAutoRangeIncludesZero(true);
    ChartPanel memoryPanel = createChartPanel(memoryChart);
    parent.add(memoryPanel);
    memoryPanel.setPreferredSize(fDefaultGraphSize);
    memoryPanel.setBorder(new TitledBorder(appContext.getString("client.stats.heap.usage")));
    memoryPanel.setToolTipText(appContext.getString("client.stats.heap.usage.tip"));
  }

  private synchronized void setupCpuSeries(TimeSeries[] cpuTimeSeries) {
    this.cpuTimeSeries = cpuTimeSeries;
    JFreeChart cpuChart = createChart(cpuTimeSeries);
    XYPlot plot = (XYPlot) cpuChart.getPlot();
    NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
    numberAxis.setRange(0.0, 1.0);
    cpuPanel.setChart(cpuChart);
    cpuPanel.setDomainZoomable(false);
    cpuPanel.setRangeZoomable(false);
  }

  private class CpuPanelWorker extends BasicWorker<TimeSeries[]> {
    private CpuPanelWorker() {
      super(new Callable<TimeSeries[]>() {
        public TimeSeries[] call() throws Exception {
          final IClient theClient = getClient();
          if (theClient != null) { return createCpusSeries(theClient); }
          return null;
        }
      });
    }

    @Override
    protected void finished() {
      Exception e = getException();
      if (e != null) {
        setupInstructions();
      } else {
        TimeSeries[] cpu = getResult();
        if (cpu.length > 0) {
          setupCpuSeries(cpu);
        } else {
          setupInstructions();
        }
      }
    }
  }

  private synchronized void setupInstructions() {
    setupHypericInstructions(cpuPanel);
  }

  private void setupCpuPanel(XContainer parent) {
    parent.add(cpuPanel = createChartPanel(null));
    cpuPanel.setPreferredSize(fDefaultGraphSize);
    cpuPanel.setBorder(new TitledBorder(appContext.getString("client.stats.cpu.usage")));
    cpuPanel.setToolTipText(appContext.getString("client.stats.cpu.usage.tip"));
    appContext.execute(new CpuPanelWorker());
  }

  private void clearAllTimeSeries() {
    ArrayList<TimeSeries> list = new ArrayList<TimeSeries>();

    if (cpuTimeSeries != null) {
      list.addAll(Arrays.asList(cpuTimeSeries));
      cpuTimeSeries = null;
    }
    if (memoryMaxSeries != null) {
      list.add(memoryMaxSeries);
      memoryMaxSeries = null;
    }
    if (memoryUsedSeries != null) {
      list.add(memoryUsedSeries);
      memoryUsedSeries = null;
    }
    if (flushRateSeries != null) {
      list.add(flushRateSeries);
      flushRateSeries = null;
    }
    if (faultRateSeries != null) {
      list.add(faultRateSeries);
      faultRateSeries = null;
    }
    if (txnRateSeries != null) {
      list.add(txnRateSeries);
      txnRateSeries = null;
    }
    if (pendingTxnsSeries != null) {
      list.add(pendingTxnsSeries);
      pendingTxnsSeries = null;
    }

    Iterator<TimeSeries> iter = list.iterator();
    while (iter.hasNext()) {
      iter.next().clear();
    }
  }

  @Override
  public synchronized void tearDown() {
    client.removePropertyChangeListener(this);
    client = null;

    super.tearDown();

    clearAllTimeSeries();
    cpuPanel = null;
  }
}
