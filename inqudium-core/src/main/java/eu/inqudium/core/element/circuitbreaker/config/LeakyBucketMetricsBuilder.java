package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.metrics.LeakyBucketMetrics;

import java.time.Instant;

public class LeakyBucketMetricsBuilder {
  private double leakRatePerSecond = 1.0;

  public LeakyBucketMetricsBuilder leakRatePerSecond(double rate) {
    this.leakRatePerSecond = rate;
    return this;
  }

  /**
   * Small bucket capacity with slow leak rate; trips very fast on failure bursts.
   */
  public LeakyBucketMetrics protective(Instant now) {
    return LeakyBucketMetrics.initial(0.1, now);
  }

  /**
   * Allows a steady stream of occasional failures while preventing sustained errors.
   */
  public LeakyBucketMetrics balanced(Instant now) {
    return LeakyBucketMetrics.initial(1.0, now);
  }

  /**
   * Large capacity for massive bursts, requiring a high error frequency to overflow.
   */
  public LeakyBucketMetrics permissive(Instant now) {
    return LeakyBucketMetrics.initial(5.0, now);
  }

  public LeakyBucketMetrics build(Instant now) {
    return LeakyBucketMetrics.initial(leakRatePerSecond, now);
  }
}