package eu.inqudium.core.retry.backoff;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jitter decorator that wraps any {@link BackoffStrategy} and randomizes its output.
 *
 * <p>Three jitter algorithms are provided (ADR-018):
 * <ul>
 *   <li><strong>Full jitter</strong>: {@code random(0, baseDelay)}. Maximum spread.</li>
 *   <li><strong>Equal jitter</strong> (recommended): {@code baseDelay/2 + random(0, baseDelay/2)}.
 *       Guaranteed minimum delay of half the backoff.</li>
 *   <li><strong>Decorrelated jitter</strong>: {@code min(cap, random(base, previousDelay × 3))}.
 *       Stateful — tracks the previous delay. Best for preventing retry storms.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class RandomizedBackoff implements BackoffStrategy {

  private final BackoffStrategy delegate;
  private final JitterFunction jitter;
  private long previousDelayMillis; // only used by decorrelated jitter

  private RandomizedBackoff(BackoffStrategy delegate, JitterFunction jitter) {
    this.delegate = delegate;
    this.jitter = jitter;
    this.previousDelayMillis = 0;
  }

  /**
   * Full jitter: {@code random(0, baseDelay)}.
   *
   * <p>Maximum spread — some retries may fire almost immediately (near zero delay).
   * Best when any spread is better than no spread.
   *
   * @param delegate the base backoff strategy
   * @return a randomized backoff with full jitter
   */
  public static RandomizedBackoff fullJitter(BackoffStrategy delegate) {
    return new RandomizedBackoff(delegate, (baseMillis, prevMillis, initialMillis) -> {
      if (baseMillis <= 0) return 0L;
      return ThreadLocalRandom.current().nextLong(0, baseMillis + 1);
    });
  }

  /**
   * Equal jitter: {@code baseDelay/2 + random(0, baseDelay/2)}.
   *
   * <p>Guaranteed minimum delay of half the computed backoff. The upper half is
   * randomized. Recommended default — balances spread and minimum delay (ADR-018).
   *
   * @param delegate the base backoff strategy
   * @return a randomized backoff with equal jitter
   */
  public static RandomizedBackoff equalJitter(BackoffStrategy delegate) {
    return new RandomizedBackoff(delegate, (baseMillis, prevMillis, initialMillis) -> {
      if (baseMillis <= 0) return 0L;
      long half = baseMillis / 2;
      long jitter = half > 0 ? ThreadLocalRandom.current().nextLong(0, half + 1) : 0;
      return half + jitter;
    });
  }

  /**
   * Decorrelated jitter: {@code random(initialInterval, previousDelay × 3)}.
   *
   * <p>Each delay depends on the previous delay, producing a more organic spread.
   * Best overall performance in high-concurrency retry storms (per AWS analysis).
   *
   * <p><strong>Stateful</strong> — this instance tracks the previous delay.
   * Do not share across concurrent retry sequences. Each Retry element creates
   * its own instance per call.
   *
   * @param delegate the base backoff strategy (used for the first attempt only)
   * @return a randomized backoff with decorrelated jitter
   */
  public static RandomizedBackoff decorrelatedJitter(BackoffStrategy delegate) {
    return new RandomizedBackoff(delegate, null /* special handling in computeDelay */);
  }

  @Override
  public Duration computeDelay(int attemptNumber, Duration initialInterval) {
    long initialMillis = initialInterval.toMillis();

    if (jitter == null) {
      // Decorrelated jitter — special handling
      return computeDecorrelated(attemptNumber, initialMillis);
    }

    long baseMillis = delegate.computeDelay(attemptNumber, initialInterval).toMillis();
    long result = jitter.apply(baseMillis, previousDelayMillis, initialMillis);
    previousDelayMillis = result;
    return Duration.ofMillis(result);
  }

  private Duration computeDecorrelated(int attemptNumber, long initialMillis) {
    if (attemptNumber == 1 || previousDelayMillis == 0) {
      // First attempt — use delegate for the base, then apply initial jitter
      long baseMillis = delegate.computeDelay(attemptNumber, Duration.ofMillis(initialMillis)).toMillis();
      long result = baseMillis > initialMillis
          ? ThreadLocalRandom.current().nextLong(initialMillis, baseMillis + 1)
          : initialMillis;
      previousDelayMillis = result;
      return Duration.ofMillis(result);
    }

    // Subsequent attempts: random(initialInterval, previousDelay × 3)
    long upperBound = previousDelayMillis * 3;
    if (upperBound <= initialMillis) upperBound = initialMillis + 1;
    long result = ThreadLocalRandom.current().nextLong(initialMillis, upperBound + 1);
    previousDelayMillis = result;
    return Duration.ofMillis(result);
  }

  @Override
  public String toString() {
    return "RandomizedBackoff{delegate=" + delegate + '}';
  }

  @FunctionalInterface
  private interface JitterFunction {
    long apply(long baseMillis, long previousDelayMillis, long initialMillis);
  }
}
