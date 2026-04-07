package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.metrics.ConsecutiveFailuresMetrics;

public class ConsecutiveFailuresConfigBuilder {
  private Integer initialConsecutiveFailures;
  private Double failureRateThreshold;

  public ConsecutiveFailuresConfigBuilder initialConsecutiveFailures(int count) {
    this.initialConsecutiveFailures = count;
    return this;
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 3-5.
   * Best for critical low-latency dependencies where a short burst of errors
   * indicates a complete service outage.
   */
  public ConsecutiveFailuresConfig buildProtective() {
    return new ConsecutiveFailuresConfig(0);
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 10-20.
   * Standard setting for distributed systems to tolerate small network
   * hiccups while catching sustained connectivity issues.
   */
  public ConsecutiveFailuresConfig buildBalanced() {
    return new ConsecutiveFailuresConfig(0);
  }

  /**
   * Starts with 0 failures.
   * Recommended global threshold: 50+.
   * Use this for highly unstable legacy systems or non-critical
   * background tasks that should almost never trigger the breaker.
   */
  public ConsecutiveFailuresConfig buildPermissive() {
    return new ConsecutiveFailuresConfig(0);
  }

  public ConsecutiveFailuresConfig build() {
    return new ConsecutiveFailuresConfig(initialConsecutiveFailures);
  }
}