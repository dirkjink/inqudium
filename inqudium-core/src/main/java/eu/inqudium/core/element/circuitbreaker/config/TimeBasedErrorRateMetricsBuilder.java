package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedErrorRateMetrics;

import java.time.Instant;

public class TimeBasedErrorRateMetricsBuilder {
  private int windowSizeInSeconds = 10;
  private int minimumNumberOfCalls = 5;

  public TimeBasedErrorRateMetricsBuilder windowSizeInSeconds(int seconds) {
    this.windowSizeInSeconds = seconds;
    return this;
  }

  public TimeBasedErrorRateMetricsBuilder minimumNumberOfCalls(int calls) {
    this.minimumNumberOfCalls = calls;
    return this;
  }

  /**
   * Reacts quickly to outages within a short 10-second observation window.
   */
  public TimeBasedErrorRateMetrics protective(Instant now) {
    return TimeBasedErrorRateMetrics.initial(10, 5, now);
  }

  /**
   * Industry standard 1-minute window for stable error rate calculation.
   */
  public TimeBasedErrorRateMetrics balanced(Instant now) {
    return TimeBasedErrorRateMetrics.initial(60, 20, now);
  }

  /**
   * Long 5-minute window to ignore temporary network flakiness.
   */
  public TimeBasedErrorRateMetrics permissive(Instant now) {
    return TimeBasedErrorRateMetrics.initial(300, 100, now);
  }

  public TimeBasedErrorRateMetrics build(Instant now) {
    return TimeBasedErrorRateMetrics.initial(windowSizeInSeconds, minimumNumberOfCalls, now);
  }
}