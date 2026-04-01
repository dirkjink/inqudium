package eu.inqudium.core.circuitbreaker.metrics;

import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;

/**
 * Immutable implementation of the gradual decay algorithm.
 * One success heals exactly one failure.
 */
public record GradualDecayMetrics(int failureCount) implements FailureMetrics {

  public static GradualDecayMetrics initial() {
    return new GradualDecayMetrics(0);
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    // Prevent failure count from dropping below zero
    return new GradualDecayMetrics(Math.max(0, failureCount - 1));
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    return new GradualDecayMetrics(failureCount + 1);
  }

  @Override
  public boolean isThresholdReached(CircuitBreakerConfig config, Instant now) {
    return failureCount >= config.failureThreshold();
  }

  @Override
  public FailureMetrics reset(Instant now) {
    return initial();
  }

  @Override
  public String getTripReason(CircuitBreakerConfig config, Instant now) {
    return "Failure threshold reached: Current failure count is %d (Threshold: %d)."
        .formatted(failureCount, config.failureThreshold());
  }
}
