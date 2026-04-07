package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.metrics.ContinuousTimeEwmaMetrics;

import java.time.Duration;
import java.time.Instant;

public class ContinuousTimeEwmaMetricsBuilder {
  private Duration timeConstant = Duration.ofSeconds(30);
  private int minimumNumberOfCalls = 5;

  public ContinuousTimeEwmaMetricsBuilder timeConstant(Duration tau) {
    this.timeConstant = tau;
    return this;
  }

  public ContinuousTimeEwmaMetricsBuilder minimumNumberOfCalls(int calls) {
    this.minimumNumberOfCalls = calls;
    return this;
  }

  /**
   * Short time constant (Tau) makes the rate decay or increase very rapidly.
   */
  public ContinuousTimeEwmaMetrics protective(Instant now) {
    return ContinuousTimeEwmaMetrics.initial(Duration.ofSeconds(5), 5, now);
  }

  /**
   * Medium time constant suitable for typical cloud service latencies and patterns.
   */
  public ContinuousTimeEwmaMetrics balanced(Instant now) {
    return ContinuousTimeEwmaMetrics.initial(Duration.ofSeconds(30), 10, now);
  }

  /**
   * Long time constant that provides a very stable, long-term failure average.
   */
  public ContinuousTimeEwmaMetrics permissive(Instant now) {
    return ContinuousTimeEwmaMetrics.initial(Duration.ofMinutes(5), 50, now);
  }

  public ContinuousTimeEwmaMetrics build(Instant now) {
    return ContinuousTimeEwmaMetrics.initial(timeConstant, minimumNumberOfCalls, now);
  }
}