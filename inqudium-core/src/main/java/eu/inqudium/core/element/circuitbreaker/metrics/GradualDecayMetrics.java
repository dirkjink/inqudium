package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;

/**
 * Immutable implementation of the gradual decay algorithm.
 * One success heals exactly one failure.
 */
public record GradualDecayMetrics(long failureThreshold, int failureCount) implements FailureMetrics {

  public static GradualDecayMetrics initial(double failureThreshold) {
    return new GradualDecayMetrics(Math.round(failureThreshold), 0);
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    // Prevent failure count from dropping below zero
    return new GradualDecayMetrics(failureThreshold, Math.max(0, failureCount - 1));
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    return new GradualDecayMetrics(failureThreshold,failureCount + 1);
  }

  @Override
  public boolean isThresholdReached(Instant now) {
    return failureCount >= failureThreshold;
  }

  @Override
  public FailureMetrics reset(Instant now) {
    return new GradualDecayMetrics(failureThreshold,0);
  }

  @Override
  public String getTripReason(Instant now) {
    return "Failure threshold reached: Current failure count is %d (Threshold: %d)."
        .formatted(failureCount, failureThreshold);
  }
}
