package eu.inqudium.core.circuitbreaker.metrics;

import eu.inqudium.core.algo.RequestBasedEwma;
import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;

/**
 * Immutable implementation of a FailureMetrics strategy using a Request-Based EWMA.
 *
 * <p>This strategy evaluates the failure rate as an exponentially weighted moving average,
 * decaying based on the sequence of discrete requests rather than chronological time.
 * A failure is recorded as a sample of 1.0, and a success as 0.0.
 *
 * <p>Note: The {@link CircuitBreakerConfig#failureThreshold()} is interpreted as a
 * percentage from 1 to 100 (e.g., a threshold of 50 means 50% failure rate, which is 0.5).
 */
public record RequestBasedEwmaMetrics(
    RequestBasedEwma ewmaCalculator,
    int minimumNumberOfCalls,
    double currentRate,
    int callsCount
) implements FailureMetrics {

  /**
   * Creates a new initial request-based EWMA metrics state.
   *
   * @param smoothingFactor      The alpha factor (0.01 to 1.0) controlling how heavily new samples weigh.
   * @param minimumNumberOfCalls The minimum number of calls before the threshold is evaluated.
   * @return a fresh metrics instance
   */
  public static RequestBasedEwmaMetrics initial(
      double smoothingFactor,
      int minimumNumberOfCalls
  ) {
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new RequestBasedEwmaMetrics(
        new RequestBasedEwma(smoothingFactor),
        minimumNumberOfCalls,
        0.0,
        0
    );
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    // Record a success as a 0.0 sample. The timestamp is ignored for request-based EWMA.
    return recordOutcome(0.0);
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    // Record a failure as a 1.0 sample. The timestamp is ignored for request-based EWMA.
    return recordOutcome(1.0);
  }

  /**
   * Internal method to calculate the new state immutably based on the incoming sample.
   */
  private RequestBasedEwmaMetrics recordOutcome(double sample) {
    // Calculate the new exponentially smoothed rate based purely on the new sample
    double newRate = ewmaCalculator.calculate(currentRate, sample);

    // Increment the call count, capping it at the minimum required calls to prevent overflow
    int newCount = Math.min(minimumNumberOfCalls, callsCount + 1);

    return new RequestBasedEwmaMetrics(
        ewmaCalculator,
        minimumNumberOfCalls,
        newRate,
        newCount
    );
  }

  @Override
  public boolean isThresholdReached(CircuitBreakerConfig config, Instant now) {
    // The circuit breaker cannot trip if we haven't collected enough samples yet
    if (callsCount < minimumNumberOfCalls) {
      return false;
    }

    // Convert the integer config threshold (e.g., 50) to a double percentage (e.g., 0.5)
    double rateThreshold = config.failureThreshold() / 100.0;

    return currentRate >= rateThreshold;
  }

  @Override
  public FailureMetrics reset(Instant now) {
    // Return a completely fresh instance using the existing configuration
    return new RequestBasedEwmaMetrics(
        ewmaCalculator,
        minimumNumberOfCalls,
        0.0,
        0
    );
  }

  @Override
  public String getTripReason(CircuitBreakerConfig config, Instant now) {
    return "Request-based EWMA threshold reached: Current failure rate is %.1f%% (Threshold: %d%%). Alpha: %.2f."
        .formatted(currentRate * 100.0, config.failureThreshold(), ewmaCalculator.alpha());
  }
}
