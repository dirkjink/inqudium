package eu.inqudium.core.element.circuitbreaker.metrics;

/**
 * Immutable implementation of a gradual-decay failure tracking strategy.
 *
 * <h2>Algorithm Overview</h2>
 * <p>Unlike {@link ConsecutiveFailuresMetrics}, which resets entirely on a single success,
 * this algorithm treats successes and failures symmetrically: each failure increments the
 * counter by one, and each success decrements it by one (floored at zero). The circuit
 * trips when the counter reaches or exceeds {@code maxFailureCount}.
 *
 * <p>This models a system where recovery is gradual — a downstream service that was
 * failing heavily needs to demonstrate sustained health (many successes) before the
 * accumulated "damage" is fully healed.
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>You want failures to accumulate and not be instantly forgiven by a single success.</li>
 *   <li>The downstream service can be partially degraded, returning a mix of successes
 *       and failures, and you want the circuit to reflect that net health.</li>
 *   <li>A simple, time-independent counter model is sufficient.</li>
 * </ul>
 *
 * <h2>Time Independence</h2>
 * <p>Like {@link ConsecutiveFailuresMetrics}, this algorithm does not use wall-clock time.
 * The {@code nowNanos} parameter is accepted but ignored everywhere.
 *
 * <h2>Boundary Behavior</h2>
 * <ul>
 *   <li>The failure count is <em>not</em> capped at {@code maxFailureCount}; it can grow
 *       beyond the threshold. This means that after a large burst of failures, an equal
 *       number of successes is needed to bring the counter back below the threshold.</li>
 *   <li>The failure count is floored at zero — successes cannot produce a negative count.</li>
 * </ul>
 *
 * @param maxFailureCount the threshold; when {@code failureCount >= maxFailureCount} the circuit opens
 * @param failureCount    the current net failure count (failures minus healed-by-successes)
 */
public record GradualDecayMetrics(
    int maxFailureCount,
    int failureCount
) implements FailureMetrics {

  /**
   * Creates a fresh instance with zero accumulated failures.
   *
   * @param maxFailureCount the threshold at which the circuit should trip
   * @return a new {@code GradualDecayMetrics} with {@code failureCount == 0}
   */
  public static GradualDecayMetrics initial(int maxFailureCount) {
    return new GradualDecayMetrics(maxFailureCount, 0);
  }

  /**
   * Records a successful call by decrementing the failure count by one.
   * The count is floored at zero via {@link Math#max(int, int)}.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   * @return a new instance with {@code failureCount} reduced by one (minimum 0)
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return new GradualDecayMetrics(maxFailureCount, Math.max(0, failureCount - 1));
  }

  /**
   * Records a failed call by incrementing the failure count by one.
   * No upper cap is enforced — the count may exceed {@code maxFailureCount}.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   * @return a new instance with {@code failureCount} incremented by one
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return new GradualDecayMetrics(maxFailureCount, failureCount + 1);
  }

  /**
   * Returns {@code true} if the accumulated failure count has reached or exceeded
   * the configured maximum.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    return failureCount >= maxFailureCount;
  }

  /**
   * Resets the failure count to zero while preserving the threshold configuration.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return new GradualDecayMetrics(maxFailureCount, 0);
  }

  /**
   * Produces a diagnostic message showing the current failure count versus the threshold.
   *
   * @param nowNanos ignored — this algorithm is time-independent
   * @return a formatted string such as
   *         "Failure threshold reached: Current failure count is 10 (Threshold: 10)."
   */
  @Override
  public String getTripReason(long nowNanos) {
    return "Failure threshold reached: Current failure count is %d (Threshold: %d)."
        .formatted(failureCount, maxFailureCount);
  }
}
