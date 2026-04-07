package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.List;

/**
 * Immutable implementation of a Composite FailureMetrics strategy.
 *
 * <p>Combines multiple metrics with OR-logic: if ANY underlying metric
 * signals that the threshold is reached, the composite metric trips the circuit.
 */
public record CompositeFailureMetrics(List<FailureMetrics> delegates) implements FailureMetrics {

  public static CompositeFailureMetrics of(FailureMetrics... metrics) {
    if (metrics == null || metrics.length == 0) {
      throw new IllegalArgumentException("At least one FailureMetrics instance must be provided");
    }
    return new CompositeFailureMetrics(List.of(metrics));
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    List<FailureMetrics> updatedDelegates = delegates.stream()
        .map(metric -> metric.recordSuccess(nowNanos))
        .toList();
    return new CompositeFailureMetrics(updatedDelegates);
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    List<FailureMetrics> updatedDelegates = delegates.stream()
        .map(metric -> metric.recordFailure(nowNanos))
        .toList();
    return new CompositeFailureMetrics(updatedDelegates);
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    for (FailureMetrics metric : delegates) {
      if (metric.isThresholdReached(nowNanos)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    List<FailureMetrics> resetDelegates = delegates.stream()
        .map(metric -> metric.reset(nowNanos))
        .toList();
    return new CompositeFailureMetrics(resetDelegates);
  }

  @Override
  public String getTripReason(long nowNanos) {
    StringBuilder reasonBuilder = new StringBuilder("Composite threshold reached. Triggering component(s): ");

    List<String> triggeringReasons = delegates.stream()
        .filter(metric -> metric.isThresholdReached(nowNanos))
        .map(metric -> "[" + metric.getClass().getSimpleName() + ": " + metric.getTripReason(nowNanos) + "]")
        .toList();

    reasonBuilder.append(String.join(", ", triggeringReasons));
    return reasonBuilder.toString();
  }
}
