package eu.inqudium.core.element.circuitbreaker.metrics;

/**
 * Immutable implementation of a consecutive failures algorithm.
 *
 * <p>Trips the circuit breaker only if a specific number of failures occur
 * strictly back-to-back. A single successful call resets the counter completely.
 */
public record ConsecutiveFailuresMetrics(
    int maxConsecutiveFailures,
    int consecutiveFailures
) implements FailureMetrics {

  public static ConsecutiveFailuresMetrics initial(int maxConsecutiveFailures) {
    return new ConsecutiveFailuresMetrics(maxConsecutiveFailures, 0);
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return new ConsecutiveFailuresMetrics(maxConsecutiveFailures, 0);
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return new ConsecutiveFailuresMetrics(maxConsecutiveFailures, consecutiveFailures + 1);
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    return consecutiveFailures >= maxConsecutiveFailures;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return initial(maxConsecutiveFailures);
  }

  @Override
  public String getTripReason(long nowNanos) {
    return "Consecutive failure threshold reached: Received %d failures in a row (Threshold: %d)."
        .formatted(consecutiveFailures, maxConsecutiveFailures);
  }
}
