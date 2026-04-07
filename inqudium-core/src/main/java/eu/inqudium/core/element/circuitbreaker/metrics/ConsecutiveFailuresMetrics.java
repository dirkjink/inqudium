package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;

/**
 * Immutable implementation of a consecutive failures algorithm.
 * * <p>This strategy trips the circuit breaker only if a specific number
 * of failures occur strictly back-to-back. A single successful call
 * resets the consecutive failure counter completely.
 * * <p>This is highly memory efficient (O(1)) and useful for systems
 * that can tolerate occasional isolated failures but need to trip
 * immediately upon a hard outage.
 */
public record ConsecutiveFailuresMetrics(long failureThreshold, int consecutiveFailures) implements FailureMetrics {

  /**
   * Creates the initial state with zero consecutive failures.
   *
   * @return a fresh metrics instance
   */
  public static ConsecutiveFailuresMetrics initial(double failureThreshold) {
    return new ConsecutiveFailuresMetrics(Math.round(failureThreshold), 0);
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    // A single success breaks the chain and resets the counter to zero
    return new ConsecutiveFailuresMetrics(failureThreshold, 0);
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    // Increment the streak of consecutive failures
    return new ConsecutiveFailuresMetrics(failureThreshold, consecutiveFailures + 1);
  }

  @Override
  public boolean isThresholdReached(Instant now) {
    // Evaluate against the configured failure threshold
    return consecutiveFailures >= failureThreshold;
  }

  @Override
  public FailureMetrics reset(Instant now) {
    // Resetting the state is identical to creating the initial state
    return initial(Math.round(failureThreshold));
  }

  @Override
  public String getTripReason(Instant now) {
    return "Consecutive failure threshold reached: Received %d failures in a row (Threshold: %d)."
        .formatted(consecutiveFailures, failureThreshold);
  }
}