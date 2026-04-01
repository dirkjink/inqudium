package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;
import java.util.List;

/**
 * Immutable implementation of a Composite FailureMetrics strategy.
 *
 * <p>This allows combining multiple metrics (e.g., a sliding window AND a
 * consecutive failures counter). By default, it uses an OR-logic for the
 * threshold evaluation: if ANY of the underlying metrics signals that the
 * threshold is reached, the composite metric trips the circuit.
 */
public record CompositeFailureMetrics(List<FailureMetrics> delegates) implements FailureMetrics {

  /**
   * Creates a new composite metric from the provided underlying metrics.
   *
   * @param metrics the metrics to combine
   * @return a new composite metric instance
   * @throws IllegalArgumentException if no metrics are provided
   */
  public static CompositeFailureMetrics of(FailureMetrics... metrics) {
    if (metrics == null || metrics.length == 0) {
      throw new IllegalArgumentException("At least one FailureMetrics instance must be provided");
    }
    // List.of creates an immutable copy to guarantee the functional core constraints
    return new CompositeFailureMetrics(List.of(metrics));
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    // Map over all delegates and record the success immutably
    List<FailureMetrics> updatedDelegates = delegates.stream()
        .map(metric -> metric.recordSuccess(now))
        .toList();

    return new CompositeFailureMetrics(updatedDelegates);
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    // Map over all delegates and record the failure immutably
    List<FailureMetrics> updatedDelegates = delegates.stream()
        .map(metric -> metric.recordFailure(now))
        .toList();

    return new CompositeFailureMetrics(updatedDelegates);
  }

  @Override
  public boolean isThresholdReached(CircuitBreakerConfig config, Instant now) {
    // OR-Logic: Trip the circuit if any of the underlying metrics reaches its threshold
    for (FailureMetrics metric : delegates) {
      if (metric.isThresholdReached(config, now)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public FailureMetrics reset(Instant now) {
    // Reset all underlying metrics
    List<FailureMetrics> resetDelegates = delegates.stream()
        .map(metric -> metric.reset(now))
        .toList();

    return new CompositeFailureMetrics(resetDelegates);
  }

  @Override
  public String getTripReason(CircuitBreakerConfig config, Instant now) {
    StringBuilder reasonBuilder = new StringBuilder("Composite threshold reached. Triggering component(s): ");

    List<String> triggeringReasons = delegates.stream()
        .filter(metric -> metric.isThresholdReached(config, now))
        .map(metric -> "[" + metric.getClass().getSimpleName() + ": " + metric.getTripReason(config, now) + "]")
        .toList();

    reasonBuilder.append(String.join(", ", triggeringReasons));
    return reasonBuilder.toString();
  }
}
