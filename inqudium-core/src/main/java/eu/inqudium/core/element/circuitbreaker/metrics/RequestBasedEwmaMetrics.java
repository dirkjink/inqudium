package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.algo.RequestBasedEwma;

/**
 * Immutable implementation of a FailureMetrics strategy using a Request-Based EWMA.
 *
 * <p>Decays based on the sequence of discrete requests rather than chronological time.
 * A failure is recorded as a sample of 1.0, and a success as 0.0.
 *
 * <p>The failure threshold is interpreted as a percentage from 1 to 100.
 */
public record RequestBasedEwmaMetrics(
    double failureRatePercent,
    RequestBasedEwma ewmaCalculator,
    int minimumNumberOfCalls,
    double currentRate,
    int callsCount
) implements FailureMetrics {

  public static RequestBasedEwmaMetrics initial(
      double failureThreshold,
      double smoothingFactor,
      int minimumNumberOfCalls
  ) {
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new RequestBasedEwmaMetrics(
        Math.round(failureThreshold),
        new RequestBasedEwma(smoothingFactor),
        minimumNumberOfCalls,
        0.0,
        0
    );
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return recordOutcome(0.0);
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return recordOutcome(1.0);
  }

  private RequestBasedEwmaMetrics recordOutcome(double sample) {
    double newRate = ewmaCalculator.calculate(currentRate, sample);
    int newCount = Math.min(minimumNumberOfCalls, callsCount + 1);
    return new RequestBasedEwmaMetrics(failureRatePercent, ewmaCalculator, minimumNumberOfCalls, newRate, newCount);
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    if (callsCount < minimumNumberOfCalls) {
      return false;
    }
    double rateThreshold = failureRatePercent / 100.0;
    return currentRate >= rateThreshold;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return new RequestBasedEwmaMetrics(failureRatePercent, ewmaCalculator, minimumNumberOfCalls, 0.0, 0);
  }

  @Override
  public String getTripReason(long nowNanos) {
    return "Request-based EWMA threshold reached: Current failure rate is %.1f%% (Threshold: %f%%). Alpha: %.2f."
        .formatted(currentRate * 100.0, failureRatePercent, ewmaCalculator.alpha());
  }
}
