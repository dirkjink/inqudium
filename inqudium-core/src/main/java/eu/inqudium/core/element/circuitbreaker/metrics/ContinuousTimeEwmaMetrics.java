package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.algo.ContinuousTimeEwma;

import java.time.Duration;

/**
 * Immutable implementation of a FailureMetrics strategy using a Continuous-Time EWMA.
 *
 * <p>Evaluates the failure rate as an exponentially weighted moving average,
 * decaying precisely based on chronological time.
 *
 * <p>The failure threshold is interpreted as a percentage from 1 to 100.
 */
public record ContinuousTimeEwmaMetrics(
    long failureThreshold,
    ContinuousTimeEwma ewmaCalculator,
    int minimumNumberOfCalls,
    double currentRate,
    int callsCount,
    long lastUpdateNanos
) implements FailureMetrics {

  public static ContinuousTimeEwmaMetrics initial(
      double failureThreshold,
      Duration timeConstant,
      int minimumNumberOfCalls,
      long nowNanos
  ) {
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new ContinuousTimeEwmaMetrics(
        Math.round(failureThreshold),
        new ContinuousTimeEwma(timeConstant),
        minimumNumberOfCalls,
        0.0,
        0,
        nowNanos
    );
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return recordOutcome(nowNanos, 0.0);
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return recordOutcome(nowNanos, 1.0);
  }

  private ContinuousTimeEwmaMetrics recordOutcome(long nowNanos, double sample) {
    double newRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, nowNanos, sample);
    int newCount = Math.min(minimumNumberOfCalls, callsCount + 1);
    long newLastUpdateNanos = Math.max(lastUpdateNanos, nowNanos);

    return new ContinuousTimeEwmaMetrics(
        failureThreshold, ewmaCalculator, minimumNumberOfCalls,
        newRate, newCount, newLastUpdateNanos
    );
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    if (callsCount < minimumNumberOfCalls) {
      return false;
    }
    double decayedRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, nowNanos, 0.0);
    double rateThreshold = failureThreshold / 100.0;
    return decayedRate >= rateThreshold;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return new ContinuousTimeEwmaMetrics(
        failureThreshold, ewmaCalculator, minimumNumberOfCalls,
        0.0, 0, nowNanos
    );
  }

  @Override
  public String getTripReason(long nowNanos) {
    double decayedRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, nowNanos, 0.0);
    return "Continuous-time EWMA threshold reached: Current failure rate is %.1f%% (Threshold: %d%%). Time Constant (Tau): %s."
        .formatted(decayedRate * 100.0, failureThreshold, ewmaCalculator.tauDurationNanos());
  }
}
