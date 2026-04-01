package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;

/**
 * Strategy interface for tracking failures and determining if the circuit should open.
 * Implementations must be immutable to fit into the functional core.
 */
public interface FailureMetrics {

  /**
   * Records a successful call and returns the updated metrics state.
   */
  FailureMetrics recordSuccess(Instant now);

  /**
   * Records a failed call and returns the updated metrics state.
   */
  FailureMetrics recordFailure(Instant now);

  /**
   * Evaluates if the failure threshold has been reached based on the current state and configuration.
   */
  boolean isThresholdReached(CircuitBreakerConfig config, Instant now);

  /**
   * Resets the metrics to their initial state (e.g., when transitioning to CLOSED).
   */
  FailureMetrics reset(Instant now);

  /**
   * Returns a human-readable, detailed explanation of why the threshold was reached.
   * <p>This is highly valuable for DevOps engineers and logging. It should include
   * the concrete numbers (e.g., current rate vs. threshold) that led to the trip.
   */
  String getTripReason(CircuitBreakerConfig config, Instant now);
}
