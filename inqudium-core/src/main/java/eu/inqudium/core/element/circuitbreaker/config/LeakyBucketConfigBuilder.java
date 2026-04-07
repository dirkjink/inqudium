package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.element.circuitbreaker.metrics.LeakyBucketMetrics;

import java.time.Instant;

public class LeakyBucketConfigBuilder  extends ExtensionBuilder<LeakyBucketConfig> {
  private Double leakRatePerSecond;

  public LeakyBucketConfigBuilder leakRatePerSecond(double rate) {
    this.leakRatePerSecond = rate;
    return this;
  }

  /**
   * Small bucket capacity with slow leak rate; trips very fast on failure bursts.
   */
  public LeakyBucketConfigBuilder protective() {
    this.leakRatePerSecond = 0.1;
    return this;
  }

  /**
   * Allows a steady stream of occasional failures while preventing sustained errors.
   */
  public LeakyBucketConfigBuilder balanced() {
    this.leakRatePerSecond = 1.0;
    return this;
  }

  /**
   * Large capacity for massive bursts, requiring a high error frequency to overflow.
   */
  public LeakyBucketConfigBuilder permissive() {
    this.leakRatePerSecond = 5.0;
    return this;
  }

  public LeakyBucketConfig build() {
    if (leakRatePerSecond == null) {
      balanced();
    }
    return new LeakyBucketConfig(leakRatePerSecond);
  }
}