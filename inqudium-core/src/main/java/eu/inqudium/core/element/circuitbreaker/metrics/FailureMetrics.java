package eu.inqudium.core.element.circuitbreaker.metrics;

/**
 * Strategy interface for tracking failures and determining if the circuit should open.
 * Implementations must be immutable to fit into the functional core.
 *
 * <p>All time parameters are expressed as nanoseconds obtained from
 * {@link eu.inqudium.core.time.InqNanoTimeSource#now()}, ensuring
 * deterministic testability without {@code Thread.sleep()} (ADR-016).
 */
public interface FailureMetrics {

  /**
   * Records a successful call and returns the updated metrics state.
   */
  FailureMetrics recordSuccess(long nowNanos);

  /**
   * Records a failed call and returns the updated metrics state.
   */
  FailureMetrics recordFailure(long nowNanos);

  /**
   * Evaluates if the failure threshold has been reached based on the current state and configuration.
   */
  boolean isThresholdReached(long nowNanos);

  /**
   * Resets the metrics to their initial state (e.g., when transitioning to CLOSED).
   */
  FailureMetrics reset(long nowNanos);

  /**
   * Returns a human-readable, detailed explanation of why the threshold was reached.
   * <p>This is highly valuable for DevOps engineers and logging. It should include
   * the concrete numbers (e.g., current rate vs. threshold) that led to the trip.
   */
  String getTripReason(long nowNanos);
}
