package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.metrics.SlidingWindowMetrics;

public class SlidingWindowMetricsBuilder {
  private int windowSize = 10;
  private int minimumNumberOfCalls = 5;

  public SlidingWindowMetricsBuilder windowSize(int windowSize) {
    this.windowSize = windowSize;
    return this;
  }

  public SlidingWindowMetricsBuilder minimumNumberOfCalls(int minimumNumberOfCalls) {
    this.minimumNumberOfCalls = minimumNumberOfCalls;
    return this;
  }

  /**
   * Highly sensitive configuration for critical systems where even
   * a few failures in a small window should trip the circuit.
   */
  public SlidingWindowMetrics protective() {
    return SlidingWindowMetrics.initial(10, 5);
  }

  /**
   * Standard configuration for most microservices, balancing
   * responsiveness with tolerance for isolated spikes.
   */
  public SlidingWindowMetrics balanced() {
    return SlidingWindowMetrics.initial(50, 20);
  }

  /**
   * High-volume configuration that requires a significant history
   * of calls before making an opening decision.
   */
  public SlidingWindowMetrics permissive() {
    return SlidingWindowMetrics.initial(200, 50);
  }


  public SlidingWindowMetrics build() {
    return SlidingWindowMetrics.initial(windowSize, minimumNumberOfCalls);
  }
}
