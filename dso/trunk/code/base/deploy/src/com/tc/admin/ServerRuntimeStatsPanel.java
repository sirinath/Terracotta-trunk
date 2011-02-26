/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import static com.tc.admin.model.IClusterNode.POLLED_ATTR_CPU_USAGE;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_MAX_MEMORY;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_OBJECT_FAULT_RATE;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_OBJECT_FLUSH_RATE;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_OFFHEAP_MAP_MEMORY;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_OFFHEAP_MAX_MEMORY;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_OFFHEAP_OBJECT_MEMORY;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_OFFHEAP_USED_MEMORY;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_TRANSACTION_RATE;
import static com.tc.admin.model.IClusterNode.POLLED_ATTR_USED_MEMORY;
import static com.tc.admin.model.IServer.POLLED_ATTR_OFFHEAP_FAULT_RATE;
import static com.tc.admin.model.IServer.POLLED_ATTR_OFFHEAP_FLUSH_RATE;
import static com.tc.admin.model.IServer.POLLED_ATTR_ONHEAP_FAULT_RATE;
import static com.tc.admin.model.IServer.POLLED_ATTR_ONHEAP_FLUSH_RATE;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.StandardXYToolTipGenerator;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYAreaRenderer;
import org.jfree.data.time.TimeSeries;

import com.tc.admin.common.ApplicationContext;
import com.tc.admin.common.BasicWorker;
import com.tc.admin.common.StatusView;
import com.tc.admin.common.XContainer;
import com.tc.admin.common.XLabel;
import com.tc.admin.dso.BaseRuntimeStatsPanel;
import com.tc.admin.model.IServer;
import com.tc.admin.model.PolledAttributesResult;
import com.tc.statistics.StatisticData;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

public class ServerRuntimeStatsPanel extends BaseRuntimeStatsPanel {
  private IServer                  server;
  private ServerListener           serverListener;

  private TimeSeries               onHeapMaxSeries;
  private StatusView               onHeapMaxLabel;
  private TimeSeries               onHeapUsedSeries;
  private StatusView               onHeapUsedLabel;
  private TimeSeries               offHeapMapUsedSeries;
  private TimeSeries               offHeapObjectUsedSeries;

  private ChartPanel               cpuPanel;
  private TimeSeries[]             cpuTimeSeries;

  private TimeSeries               flushRateSeries;
  private XLabel                   flushRateLabel;
  private TimeSeries               faultRateSeries;
  private XLabel                   faultRateLabel;
  private TimeSeries               txnRateSeries;
  private XLabel                   txnRateLabel;
  private TimeSeries               onHeapFaultRateSeries;
  private StatusView               onHeapFaultRateLabel;
  private TimeSeries               onHeapFlushRateSeries;
  private StatusView               onHeapFlushRateLabel;
  private TimeSeries               offHeapFaultRateSeries;
  private StatusView               offHeapFaultRateLabel;
  private TimeSeries               offHeapFlushRateSeries;
  private StatusView               offHeapFlushRateLabel;
  private String                   offHeapUsageTitlePattern;
  private TitledBorder             offHeapUsageTitle;
  private NumberAxis               offHeapValueAxis;

  private final String             flushRateLabelFormat        = "{0} Flushes/sec.";
  private final String             faultRateLabelFormat        = "{0} Faults/sec.";
  private final String             txnRateLabelFormat          = "{0} Txns/sec.";
  private final String             onHeapFaultRateLabelFormat  = "{0} OnHeap Faults/sec.";
  private final String             onHeapFlushRateLabelFormat  = "{0} OnHeap Flushes/sec.";
  private final String             offHeapFaultRateLabelFormat = "{0} OffHeap Faults/sec.";
  private final String             offHeapFlushRateLabelFormat = "{0} OffHeap Flushes/sec.";
  private final String             onHeapUsedLabelFormat       = "{0} OnHeap Used";
  private final String             onHeapMaxLabelFormat        = "{0} OnHeap Max";

  private static final Set<String> POLLED_ATTRIBUTE_SET        = new HashSet(
                                                                             Arrays
                                                                                 .asList(POLLED_ATTR_CPU_USAGE,
                                                                                         POLLED_ATTR_USED_MEMORY,
                                                                                         POLLED_ATTR_MAX_MEMORY,
                                                                                         POLLED_ATTR_OBJECT_FLUSH_RATE,
                                                                                         POLLED_ATTR_OBJECT_FAULT_RATE,
                                                                                         POLLED_ATTR_TRANSACTION_RATE,
                                                                                         POLLED_ATTR_ONHEAP_FLUSH_RATE,
                                                                                         POLLED_ATTR_ONHEAP_FAULT_RATE,
                                                                                         POLLED_ATTR_OFFHEAP_FLUSH_RATE,
                                                                                         POLLED_ATTR_OFFHEAP_FAULT_RATE,
                                                                                         POLLED_ATTR_OFFHEAP_MAX_MEMORY,
                                                                                         POLLED_ATTR_OFFHEAP_USED_MEMORY,
                                                                                         POLLED_ATTR_OFFHEAP_OBJECT_MEMORY,
                                                                                         POLLED_ATTR_OFFHEAP_MAP_MEMORY));

  public ServerRuntimeStatsPanel(ApplicationContext appContext, IServer server) {
    super(appContext);
    this.server = server;
    setup(chartsPanel);
    setName(server.toString());
    server.addPropertyChangeListener(serverListener = new ServerListener(server));
  }

  private class ServerListener extends AbstractServerListener {
    private ServerListener(IServer server) {
      super(server);
    }

    @Override
    protected void handleReady() {
      if (server.isReady() && cpuTimeSeries == null) {
        appContext.execute(new CpuPanelWorker());
      }
      if (!server.isReady() && isMonitoringRuntimeStats()) {
        stopMonitoringRuntimeStats();
      } else if (server.isReady() && isShowing() && getAutoStart()) {
        startMonitoringRuntimeStats();
      }
    }
  }

  synchronized IServer getServer() {
    return server;
  }

  private void addPolledAttributeListener() {
    IServer theServer = getServer();
    if (theServer != null) {
      theServer.addPolledAttributeListener(POLLED_ATTRIBUTE_SET, this);
    }
  }

  private void removePolledAttributeListener() {
    IServer theServer = getServer();
    if (theServer != null) {
      theServer.removePolledAttributeListener(POLLED_ATTRIBUTE_SET, this);
    }
  }

  @Override
  public void startMonitoringRuntimeStats() {
    if (server.isReady() && cpuTimeSeries == null) {
      appContext.execute(new CpuPanelWorker());
    }
    addPolledAttributeListener();
    super.startMonitoringRuntimeStats();
  }

  @Override
  public void stopMonitoringRuntimeStats() {
    removePolledAttributeListener();
    super.stopMonitoringRuntimeStats();
  }

  @Override
  public void attributesPolled(final PolledAttributesResult result) {
    if (tornDown.get()) { return; }
    IServer theServer = getServer();
    if (theServer != null) {
      if (!tornDown.get()) {
        handleDSOStats(result);
        handleSysStats(result);
      }
    }
  }

  private synchronized void handleDSOStats(PolledAttributesResult result) {
    IServer theServer = getServer();
    if (theServer != null) {
      tmpDate.setTime(System.currentTimeMillis());

      final Number flushRate = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_OBJECT_FLUSH_RATE);
      final Number faultRate = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_OBJECT_FAULT_RATE);
      final Number txnRate = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_TRANSACTION_RATE);
      final Number onHeapFaultRate = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_ONHEAP_FAULT_RATE);
      final Number onHeapFlushRate = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_ONHEAP_FLUSH_RATE);
      final Number offHeapFaultRate = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_OFFHEAP_FAULT_RATE);
      final Number offHeapFlushRate = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_OFFHEAP_FLUSH_RATE);

      SwingUtilities.invokeLater(new Runnable() {
        public void run() {
          updateSeries(flushRateSeries, flushRate);
          if (faultRate != null) {
            flushRateLabel.setText(MessageFormat.format(flushRateLabelFormat, convert(faultRate.longValue())));
          }

          updateSeries(faultRateSeries, faultRate);
          if (faultRate != null) {
            faultRateLabel.setText(MessageFormat.format(faultRateLabelFormat, convert(faultRate.longValue())));
          }

          updateSeries(txnRateSeries, txnRate);
          if (txnRate != null) {
            txnRateLabel.setText(MessageFormat.format(txnRateLabelFormat, convert(txnRate.longValue())));
          }

          updateSeries(onHeapFaultRateSeries, onHeapFaultRate);
          if (onHeapFaultRate != null) {
            onHeapFaultRateLabel.setText(MessageFormat.format(onHeapFaultRateLabelFormat,
                                                              convert(onHeapFaultRate.longValue())));
          }

          updateSeries(onHeapFlushRateSeries, onHeapFlushRate);
          if (onHeapFlushRate != null) {
            onHeapFlushRateLabel.setText(MessageFormat.format(onHeapFlushRateLabelFormat,
                                                              convert(onHeapFlushRate.longValue())));
          }

          updateSeries(offHeapFaultRateSeries, offHeapFaultRate);
          if (offHeapFaultRate != null) {
            offHeapFaultRateLabel.setText(MessageFormat.format(offHeapFaultRateLabelFormat,
                                                               convert(offHeapFaultRate.longValue())));
          }

          updateSeries(offHeapFlushRateSeries, offHeapFlushRate);
          if (offHeapFlushRate != null) {
            offHeapFlushRateLabel.setText(MessageFormat.format(offHeapFlushRateLabelFormat,
                                                               convert(offHeapFlushRate.longValue())));
          }
        }
      });
    }
  }

  private synchronized void handleSysStats(PolledAttributesResult result) {
    IServer theServer = getServer();
    if (theServer != null) {
      final Number maxMemory = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_MAX_MEMORY);
      final Number usedMemory = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_USED_MEMORY);
      final Number offHeapMaxMemory = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_OFFHEAP_MAX_MEMORY);
      final Number offHeapMapMemory = (Number) result.getPolledAttribute(theServer, POLLED_ATTR_OFFHEAP_MAP_MEMORY);
      final Number offHeapObjectMemory = (Number) result.getPolledAttribute(theServer,
                                                                            POLLED_ATTR_OFFHEAP_OBJECT_MEMORY);
      final StatisticData[] cpuUsageData = (StatisticData[]) result
          .getPolledAttribute(theServer, POLLED_ATTR_CPU_USAGE);

      SwingUtilities.invokeLater(new Runnable() {
        public void run() {
          updateSeries(onHeapMaxSeries, maxMemory);
          if (maxMemory != null) {
            onHeapMaxLabel.setText(MessageFormat.format(onHeapMaxLabelFormat, convert(maxMemory.longValue())));
          }

          updateSeries(onHeapUsedSeries, usedMemory);
          if (usedMemory != null) {
            onHeapUsedLabel.setText(MessageFormat.format(onHeapUsedLabelFormat, convert(usedMemory.longValue())));
          }

          long offHeapMax = 0;
          if (offHeapMaxMemory != null) {
            offHeapMax = offHeapMaxMemory.longValue();
          }
          offHeapValueAxis.setUpperBound(offHeapMax);

          updateSeries(offHeapMapUsedSeries, offHeapMapMemory);
          long mapOffHeapUsedLong = 0;
          if (offHeapMapMemory != null) {
            mapOffHeapUsedLong = offHeapMapMemory.longValue();
          }

          updateSeries(offHeapObjectUsedSeries, offHeapObjectMemory);
          long objectOffHeapUsed = 0;
          if (offHeapObjectMemory != null) {
            objectOffHeapUsed = offHeapObjectMemory.longValue();
          }

          offHeapUsageTitle.setTitle(MessageFormat.format(offHeapUsageTitlePattern, convert(offHeapMax),
                                                          convert(mapOffHeapUsedLong), convert(objectOffHeapUsed)));

          if (cpuTimeSeries != null) {
            handleCpuUsage(cpuTimeSeries, cpuUsageData);
          }
        }
      });
    }
  }

  @Override
  protected synchronized void setup(XContainer chartsPanel) {
    chartsPanel.setLayout(new GridLayout(0, 2));
    setupOnHeapPanel(chartsPanel);
    setupOffHeapPanel(chartsPanel);
    setupCpuPanel(chartsPanel);
    setupTxnRatePanel(chartsPanel);
    setupOnHeapFaultFlushPanel(chartsPanel);
    setupOffHeapFaultFlushPanel(chartsPanel);
    setupFlushRatePanel(chartsPanel);
    setupFaultRatePanel(chartsPanel);
  }

  private void setupFlushRatePanel(XContainer parent) {
    flushRateSeries = createTimeSeries("");
    ChartPanel chartPanel = createChartPanel(createChart(flushRateSeries, false));
    parent.add(chartPanel);
    chartPanel.setPreferredSize(fDefaultGraphSize);
    chartPanel.setBorder(new TitledBorder(appContext.getString("server.stats.flush.rate")));
    chartPanel.setToolTipText(appContext.getString("server.stats.flush.rate.tip"));
    chartPanel.setLayout(new BorderLayout());
    chartPanel.add(flushRateLabel = createOverlayLabel());
  }

  private void setupFaultRatePanel(XContainer parent) {
    faultRateSeries = createTimeSeries("");
    ChartPanel chartPanel = createChartPanel(createChart(faultRateSeries, false));
    parent.add(chartPanel);
    chartPanel.setPreferredSize(fDefaultGraphSize);
    chartPanel.setBorder(new TitledBorder(appContext.getString("server.stats.fault.rate")));
    chartPanel.setToolTipText(appContext.getString("server.stats.fault.rate.tip"));
    chartPanel.setLayout(new BorderLayout());
    chartPanel.add(faultRateLabel = createOverlayLabel());
  }

  private void setupTxnRatePanel(XContainer parent) {
    txnRateSeries = createTimeSeries("");
    ChartPanel chartPanel = createChartPanel(createChart(txnRateSeries, false));
    parent.add(chartPanel);
    chartPanel.setPreferredSize(fDefaultGraphSize);
    chartPanel.setBorder(new TitledBorder(appContext.getString("server.stats.transaction.rate")));
    chartPanel.setToolTipText(appContext.getString("server.stats.transaction.rate.tip"));
    chartPanel.setLayout(new BorderLayout());
    chartPanel.add(txnRateLabel = createOverlayLabel());
  }

  private void setupOnHeapFaultFlushPanel(XContainer parent) {
    onHeapFaultRateSeries = createTimeSeries(appContext.getString("dso.onheap.fault.rate"));
    onHeapFlushRateSeries = createTimeSeries(appContext.getString("dso.onheap.flush.rate"));
    ChartPanel chartPanel = createChartPanel(createChart(new TimeSeries[] { onHeapFaultRateSeries,
        onHeapFlushRateSeries }, false));
    parent.add(chartPanel);
    chartPanel.setPreferredSize(fDefaultGraphSize);
    chartPanel.setBorder(new TitledBorder(appContext.getString("server.stats.onheap.flushfault")));
    chartPanel.setToolTipText(appContext.getString("server.stats.onheap.flushfault.tip"));
    chartPanel.setLayout(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    XContainer labelHolder = new XContainer(new GridLayout(0, 1));
    labelHolder.add(onHeapFaultRateLabel = createStatusLabel(Color.red));
    labelHolder.add(onHeapFlushRateLabel = createStatusLabel(Color.blue));
    labelHolder.setOpaque(false);
    chartPanel.add(labelHolder, gbc);
  }

  private void setupOffHeapFaultFlushPanel(XContainer parent) {
    offHeapFaultRateSeries = createTimeSeries(appContext.getString("dso.offheap.fault.rate"));
    offHeapFlushRateSeries = createTimeSeries(appContext.getString("dso.offheap.flush.rate"));
    ChartPanel chartPanel = createChartPanel(createChart(new TimeSeries[] { offHeapFaultRateSeries,
        offHeapFlushRateSeries }, false));
    parent.add(chartPanel);
    chartPanel.setPreferredSize(fDefaultGraphSize);
    chartPanel.setBorder(new TitledBorder(appContext.getString("server.stats.offheap.flushfault")));
    chartPanel.setToolTipText(appContext.getString("server.stats.offheap.flushfault.tip"));
    chartPanel.setLayout(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    XContainer labelHolder = new XContainer(new GridLayout(0, 1));
    labelHolder.add(offHeapFaultRateLabel = createStatusLabel(Color.red));
    labelHolder.add(offHeapFlushRateLabel = createStatusLabel(Color.blue));
    labelHolder.setOpaque(false);
    chartPanel.add(labelHolder, gbc);
  }

  private void setupOnHeapPanel(XContainer parent) {
    onHeapMaxSeries = createTimeSeries(appContext.getString("onheap.usage.max"));
    onHeapUsedSeries = createTimeSeries(appContext.getString("onheap.usage.used"));
    JFreeChart chart = createChart(new TimeSeries[] { onHeapMaxSeries, onHeapUsedSeries }, false);
    XYPlot plot = (XYPlot) chart.getPlot();
    NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
    numberAxis.setAutoRangeIncludesZero(true);
    ChartPanel chartPanel = createChartPanel(chart);
    parent.add(chartPanel);
    chartPanel.setPreferredSize(fDefaultGraphSize);
    chartPanel.setBorder(new TitledBorder(appContext.getString("server.stats.onheap.usage")));
    chartPanel.setToolTipText(appContext.getString("server.stats.onheap.usage.tip"));
    chartPanel.setLayout(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    XContainer labelHolder = new XContainer(new GridLayout(0, 1));
    labelHolder.add(onHeapMaxLabel = createStatusLabel(Color.red));
    labelHolder.add(onHeapUsedLabel = createStatusLabel(Color.blue));
    labelHolder.setOpaque(false);
    chartPanel.add(labelHolder, gbc);
  }

  private void setupOffHeapPanel(XContainer parent) {
    offHeapMapUsedSeries = createTimeSeries(appContext.getString("offheap.map.usage"));
    offHeapObjectUsedSeries = createTimeSeries(appContext.getString("offheap.object.usage"));
    JFreeChart chart = createChart(new TimeSeries[] { offHeapObjectUsedSeries, offHeapMapUsedSeries }, true);
    XYPlot plot = (XYPlot) chart.getPlot();
    XYAreaRenderer areaRenderer2 = new XYAreaRenderer(XYAreaRenderer.AREA,
                                                      StandardXYToolTipGenerator.getTimeSeriesInstance(), null);
    plot.setRenderer(areaRenderer2);
    areaRenderer2.setSeriesPaint(0, (Color) appContext.getObject("chart.color.1"));
    areaRenderer2.setSeriesPaint(1, (Color) appContext.getObject("chart.color.2"));
    offHeapValueAxis = (NumberAxis) plot.getRangeAxis();
    offHeapValueAxis.setAutoRangeIncludesZero(true);
    ChartPanel chartPanel = createChartPanel(chart);
    String offHeapUsageLabel = appContext.getString("server.stats.offheap.usage");
    offHeapUsageTitlePattern = offHeapUsageLabel + " (max: {0}, map: {1}, object: {2})";
    offHeapUsageTitle = BorderFactory.createTitledBorder(offHeapUsageLabel);
    chartPanel.setBorder(offHeapUsageTitle);
    parent.add(chartPanel);
    chartPanel.setPreferredSize(fDefaultGraphSize);
    chartPanel.setToolTipText(appContext.getString("server.stats.offheap.usage.tip"));
  }

  private synchronized void setupCpuSeries(TimeSeries[] cpuTimeSeries) {
    this.cpuTimeSeries = cpuTimeSeries;
    JFreeChart cpuChart = createChart(cpuTimeSeries, cpuTimeSeries.length <= 4);
    XYPlot plot = (XYPlot) cpuChart.getPlot();
    NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
    numberAxis.setRange(0.0, 1.0);
    if (rangeAxisSpace != null) {
      plot.setFixedRangeAxisSpace(rangeAxisSpace);
    }
    cpuPanel.setChart(cpuChart);
    cpuPanel.setDomainZoomable(false);
    cpuPanel.setRangeZoomable(false);
  }

  private class CpuPanelWorker extends BasicWorker<TimeSeries[]> {
    private CpuPanelWorker() {
      super(new Callable<TimeSeries[]>() {
        public TimeSeries[] call() throws Exception {
          if (tornDown.get()) { return null; }
          IServer theServer = getServer();
          if (theServer != null) { return createCpusSeries(theServer); }
          return null;
        }
      }, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void finished() {
      if (tornDown.get()) { return; }
      Exception e = getException();
      if (e != null) {
        setupInstructions();
      } else {
        TimeSeries[] cpus = getResult();
        if (cpus.length > 0) {
          setupCpuSeries(cpus);
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
    cpuPanel = createChartPanel(null);
    parent.add(cpuPanel);
    cpuPanel.setPreferredSize(fDefaultGraphSize);
    cpuPanel.setBorder(new TitledBorder(appContext.getString("server.stats.cpu.usage")));
    cpuPanel.setToolTipText(appContext.getString("server.stats.cpu.usage.tip"));
  }

  private void clearAllTimeSeries() {
    ArrayList<TimeSeries> list = new ArrayList<TimeSeries>();
    if (cpuTimeSeries != null) {
      list.addAll(Arrays.asList(cpuTimeSeries));
      cpuTimeSeries = null;
    }
    if (onHeapMaxSeries != null) {
      list.add(onHeapMaxSeries);
      onHeapMaxSeries = null;
    }
    if (onHeapUsedSeries != null) {
      list.add(onHeapUsedSeries);
      onHeapUsedSeries = null;
    }
    if (offHeapObjectUsedSeries != null) {
      list.add(offHeapObjectUsedSeries);
      offHeapObjectUsedSeries = null;
    }
    if (offHeapMapUsedSeries != null) {
      list.add(offHeapMapUsedSeries);
      offHeapMapUsedSeries = null;
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
    if (onHeapFaultRateSeries != null) {
      list.add(onHeapFaultRateSeries);
      onHeapFaultRateSeries = null;
    }
    if (onHeapFlushRateSeries != null) {
      list.add(onHeapFlushRateSeries);
      onHeapFlushRateSeries = null;
    }

    Iterator<TimeSeries> iter = list.iterator();
    while (iter.hasNext()) {
      iter.next().clear();
    }
  }

  private final AtomicBoolean tornDown = new AtomicBoolean(false);

  @Override
  public synchronized void tearDown() {
    if (!tornDown.compareAndSet(false, true)) { return; }

    server.removePropertyChangeListener(serverListener);
    serverListener.tearDown();

    clearAllTimeSeries();

    synchronized (this) {
      server = null;
      serverListener = null;
      cpuPanel = null;
    }

    super.tearDown();
  }
}
