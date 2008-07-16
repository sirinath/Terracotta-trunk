/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.common;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.RangeType;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.data.xy.IntervalXYDataset;
import org.jfree.data.xy.XYDataset;

import java.text.SimpleDateFormat;

public class DemoChartFactory {
  public static JFreeChart getXYBarChart(String header, String xLabel, String yLabel, TimeSeries ts) {
    IntervalXYDataset dataset = createTimeSeriesDataset(ts);
    return createXYBarChart(header, xLabel, yLabel, dataset, false);
  }

  public static JFreeChart getXYBarChart(String header, String xLabel, String yLabel, TimeSeries[] timeseries) {
    IntervalXYDataset dataset = createTimeSeriesDataset(timeseries);
    return createXYBarChart(header, xLabel, yLabel, dataset, true);
  }

  private static JFreeChart createXYBarChart(String header, String xLabel, String yLabel, IntervalXYDataset dataset,
                                             boolean legend) {
    JFreeChart chart = ChartFactory.createXYBarChart(header, xLabel, true, yLabel, dataset, PlotOrientation.VERTICAL,
                                                     legend, true, false);

    XYPlot plot = (XYPlot) chart.getPlot();

    DateAxis axis = (DateAxis) plot.getDomainAxis();
    axis.setFixedAutoRange(30000.0);
    axis.setDateFormatOverride(new SimpleDateFormat("kk:mm:ss"));

    NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
    numberAxis.setRangeType(RangeType.POSITIVE);
    numberAxis.setAutoRangeMinimumSize(50.0);

    return chart;
  }

  public static JFreeChart getXYLineChart(String header, String xLabel, String yLabel, TimeSeries ts,
                                          boolean createLegend) {
    return getXYLineChart(header, xLabel, yLabel, new TimeSeries[] { ts }, createLegend);
  }

  public static JFreeChart getXYLineChart(String header, String xLabel, String yLabel, TimeSeries[] timeseries,
                                          boolean createLegend) {
    XYDataset dataset = createTimeSeriesDataset(timeseries);
    return createXYLineChart(header, xLabel, yLabel, dataset, createLegend);
  }

  public static JFreeChart createXYLineChart(String header, String xLabel, String yLabel, XYDataset dataset,
                                             boolean createLegend) {
    JFreeChart chart = ChartFactory.createTimeSeriesChart(header, xLabel, yLabel, dataset, createLegend, true, false);

    XYPlot plot = (XYPlot) chart.getPlot();

    ValueAxis axis = plot.getDomainAxis();
    axis.setFixedAutoRange(30000.0);
    if (axis instanceof DateAxis) {
      ((DateAxis) axis).setDateFormatOverride(new SimpleDateFormat("h:mm:ss"));
    }

    NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
    numberAxis.setRangeType(RangeType.POSITIVE);
    numberAxis.setAutoRangeMinimumSize(50.0);

    return chart;
  }

  public static JFreeChart getXYStepChart(String header, String xLabel, String yLabel, TimeSeries ts) {
    XYDataset dataset = createTimeSeriesDataset(ts);
    return createXYStepChart(header, xLabel, yLabel, dataset, false);
  }

  public static JFreeChart getXYStepChart(String header, String xLabel, String yLabel, TimeSeries[] timeseries) {
    XYDataset dataset = createTimeSeriesDataset(timeseries);
    return createXYStepChart(header, xLabel, yLabel, dataset, true);
  }

  private static JFreeChart createXYStepChart(String header, String xLabel, String yLabel, XYDataset dataset,
                                              boolean legend) {
    JFreeChart chart = ChartFactory.createXYStepChart(header, xLabel, yLabel, dataset, PlotOrientation.VERTICAL,
                                                      legend, true, false);

    XYPlot plot = (XYPlot) chart.getPlot();

    ValueAxis axis = plot.getDomainAxis();
    axis.setFixedAutoRange(30000.0);
    if (axis instanceof DateAxis) {
      ((DateAxis) axis).setDateFormatOverride(new SimpleDateFormat("h:mm:ss"));
    }

    NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
    numberAxis.setRangeType(RangeType.POSITIVE);
    numberAxis.setAutoRangeMinimumSize(50.0);

    return chart;
  }

  private static TimeSeriesCollection createTimeSeriesDataset(TimeSeries s1) {
    return createTimeSeriesDataset(new TimeSeries[] { s1 });
  }

  private static TimeSeriesCollection createTimeSeriesDataset(TimeSeries[] series) {
    TimeSeriesCollection dataset = new TimeSeriesCollection();

    for (int i = 0; i < series.length; i++) {
      dataset.addSeries(series[i]);
    }

    dataset.setDomainIsPointsInTime(true);

    return dataset;
  }
}
