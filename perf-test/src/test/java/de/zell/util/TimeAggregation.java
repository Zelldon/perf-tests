/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package de.zell.util;

import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Aggregates time measurements between two events and computes average, minimum, maximum, count,
 * and rMSSD (approximate of standard deviation)
 *
 * @author Falko Menge (Camunda)
 */
public class TimeAggregation {

  private final String startEventName;
  private final String endEventName;
  private int count;
  private double sum = 0.0;
  private double min = Double.MAX_VALUE;
  private double max = Double.MIN_VALUE;
  private double sumSquareSuccessiveDifference = 0.0;
  private double previousTime;
  private TreeMap<Double, Long> histogram = new TreeMap<Double, Long>();

  public TimeAggregation(
      final String startEventName, final String endEventName, final double time) {
    this.startEventName = startEventName;
    this.endEventName = endEventName;
    this.sum = time;
    this.min = time;
    this.max = time;
    this.previousTime = time;
    this.count = 1;
    initHistogram();
  }

  public TimeAggregation(final String startEventName, final String endEventName) {
    this.startEventName = startEventName;
    this.endEventName = endEventName;
    initHistogram();
  }

  private void initHistogram() {
    histogram.put(0.25, 0L);
    histogram.put(0.5, 0L);
    histogram.put(0.75, 0L);
    histogram.put(1.0, 0L);
    histogram.put(2.5, 0L);
    histogram.put(5.0, 0L);
    histogram.put(7.0, 0L);
    histogram.put(10.0, 0L);
    histogram.put(25.0, 0L);
    histogram.put(50.0, 0L);
    histogram.put(75.0, 0L);
    histogram.put(100.0, 0L);
    histogram.put(Double.MAX_VALUE, 0L);
  }

  public void addNanoTime(final long startTime, final long endTime) {
    add((endTime - startTime) / 1_000_000.0);
  }

  public void add(final long startTime, final long endTime) {
    add(endTime - startTime);
  }

  public void add(final double time) {
    ++count;
    sum += time;
    if (time < min) {
      min = time;
    }
    if (time > max) {
      max = time;
    }
    if (count > 1) {
      final double squareSuccessiveDifference = Math.pow(time - previousTime, 2);
      sumSquareSuccessiveDifference += squareSuccessiveDifference;
    }
    addToHistogram(time);
    previousTime = time;
  }

  private void addToHistogram(double time) {
    final NavigableSet<Double> bins = histogram.navigableKeySet();
    for (Double bin : bins) {
      if (time <= bin) {
        histogram.put(bin, (histogram.get(bin) + 1));
        break;
      }
    }
  }

  public double getMin() {
    return min;
  }

  public double getMax() {
    return max;
  }

  public int getCount() {
    return count;
  }

  /**
   * Compute arithmetic mean.
   *
   * @return average
   */
  public double getAVG() {
    return sum / count;
  }

  /**
   * Approximate standard deviation by computing the root mean square successive differences (rMSSD)
   *
   * <p>see also:
   * https://support.minitab.com/en-us/minitab/18/help-and-how-to/quality-and-process-improvement/control-charts/supporting-topics/estimating-variation/individuals-data/#mssd
   *
   * @return root mean square successive differences (rMSSD)
   */
  public double getRMSSD() {
    if (count > 1) {
      return Math.sqrt(sumSquareSuccessiveDifference / (2 * (count - 1)));
    } else {
      return 0;
    }
  }

  public String asCSV() {
    return startEventName
        + ";"
        + endEventName
        + ";"
        + getAVG()
        + ";"
        + getRMSSD()
        + ";"
        + min
        + ";"
        + max
        + ";"
        + count
        + ";"
        + histogram.values().stream().map(String::valueOf).collect(Collectors.joining(";"));
  }

  @Override
  public String toString() {
    return "{from:\""
        + startEventName
        + "\", to:\""
        + endEventName
        + "\", avg:"
        + String.format("%.1f", getAVG())
        + ", rmssd:"
        + String.format("%.1f", getRMSSD())
        + ", min:"
        + String.format("%.1f", min)
        + ", max:"
        + String.format("%.1f", max)
        + ", count:"
        + count
        + "}, histogram: "
        + histogram.toString();
  }
}
