package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.algo.ContinuousTimeEwma;
import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable implementation of a FailureMetrics strategy using a Continuous-Time EWMA.
 *
 * <p>This strategy evaluates the failure rate as an exponentially weighted moving average,
 * decaying precisely based on chronological time rather than the request count.
 * A failure is recorded as a sample of 1.0, and a success as 0.0.
 *
 * <p>Note: The {@link CircuitBreakerConfig#failureThreshold()} is interpreted as a
 * percentage from 1 to 100 (e.g., a threshold of 50 means 50% failure rate, which is 0.5).
 */
public record ContinuousTimeEwmaMetrics(
    ContinuousTimeEwma ewmaCalculator,
    int minimumNumberOfCalls,
    double currentRate,
    int callsCount,
    long lastUpdateNanos
) implements FailureMetrics {

  public static ContinuousTimeEwmaMetrics initial(
      Duration timeConstant,
      int minimumNumberOfCalls,
      Instant now
  ) {
    if (minimumNumberOfCalls <= 0) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be greater than 0");
    }
    return new ContinuousTimeEwmaMetrics(
        new ContinuousTimeEwma(timeConstant),
        minimumNumberOfCalls,
        0.0,
        0,
        toNanos(now)
    );
  }

  private static long toNanos(Instant instant) {
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    return recordOutcome(now, 0.0);
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    return recordOutcome(now, 1.0);
  }

  private ContinuousTimeEwmaMetrics recordOutcome(Instant now, double sample) {
    long nowNanos = toNanos(now);
    double newRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, nowNanos, sample);
    int newCount = Math.min(minimumNumberOfCalls, callsCount + 1);
    long newLastUpdateNanos = Math.max(lastUpdateNanos, nowNanos);

    return new ContinuousTimeEwmaMetrics(
        ewmaCalculator,
        minimumNumberOfCalls,
        newRate,
        newCount,
        newLastUpdateNanos
    );
  }

  @Override
  public boolean isThresholdReached(CircuitBreakerConfig config, Instant now) {
    if (callsCount < minimumNumberOfCalls) {
      return false;
    }

    // To evaluate the threshold accurately at this exact moment, we must calculate
    // the decayed rate. Passing 0.0 as a sample to the stateless continuous EWMA calculator
    // perfectly yields the time-decayed rate without adding new failure weight.
    double decayedRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, toNanos(now), 0.0);

    double rateThreshold = config.failureThreshold() / 100.0;
    return decayedRate >= rateThreshold;
  }

  @Override
  public FailureMetrics reset(Instant now) {
    return new ContinuousTimeEwmaMetrics(
        ewmaCalculator,
        minimumNumberOfCalls,
        0.0,
        0,
        toNanos(now)
    );
  }

  @Override
  public String getTripReason(CircuitBreakerConfig config, Instant now) {
    // Calculate decayed rate for the exact moment of the trip
    double decayedRate = ewmaCalculator.calculate(currentRate, lastUpdateNanos, toNanos(now), 0.0);
    return "Continuous-time EWMA threshold reached: Current failure rate is %.1f%% (Threshold: %d%%). Time Constant (Tau): %s."
        .formatted(decayedRate * 100.0, config.failureThreshold(), ewmaCalculator.tauDurationNanos());
  }
}