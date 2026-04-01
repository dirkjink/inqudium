package eu.inqudium.core.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;

/**
 * Strategy for computing the wait duration between retry attempts.
 *
 * <p>All implementations are immutable value objects. Stateless strategies
 * override {@link #computeDelay(int)}; stateful strategies (e.g.,
 * {@link DecorrelatedJitter}) override
 * {@link #computeDelay(int, Duration)} to incorporate the previous delay.
 *
 * <h2>Available strategies</h2>
 * <table>
 *   <tr><th>Strategy</th><th>Growth</th><th>Use case</th></tr>
 *   <tr><td>{@link Fixed}</td><td>Constant</td><td>Simple retries with predictable timing</td></tr>
 *   <tr><td>{@link Linear}</td><td>Additive</td><td>Moderate backoff for short-lived outages</td></tr>
 *   <tr><td>{@link Fibonacci}</td><td>~φ^n ≈ 1.618^n</td><td>Gentler than exponential, faster than linear</td></tr>
 *   <tr><td>{@link Exponential}</td><td>Multiplicative</td><td>Standard backoff for network retries</td></tr>
 *   <tr><td>{@link ExponentialWithJitter}</td><td>Randomised exponential</td><td>Distributed systems (avoids thundering herd)</td></tr>
 *   <tr><td>{@link DecorrelatedJitter}</td><td>Stateful random</td><td>AWS-recommended; best herd avoidance</td></tr>
 *   <tr><td>{@link NoWait}</td><td>Zero</td><td>Immediate retries (tests, local ops)</td></tr>
 *   <tr><td>{@link Custom}</td><td>User-defined</td><td>Full control without subclassing</td></tr>
 * </table>
 */
public sealed interface BackoffStrategy {

  // ======================== Factory methods ========================

  static BackoffStrategy fixedDelay(Duration delay) {
    return new Fixed(delay);
  }

  static BackoffStrategy linear(Duration initialDelay, Duration increment) {
    return new Linear(initialDelay, increment, Duration.ofSeconds(30));
  }

  static BackoffStrategy linear(Duration initialDelay, Duration increment, Duration maxDelay) {
    return new Linear(initialDelay, increment, maxDelay);
  }

  static BackoffStrategy fibonacci(Duration initialDelay) {
    return new Fibonacci(initialDelay, Duration.ofSeconds(30));
  }

  static BackoffStrategy fibonacci(Duration initialDelay, Duration maxDelay) {
    return new Fibonacci(initialDelay, maxDelay);
  }

  static BackoffStrategy exponential(Duration initialDelay) {
    return new Exponential(initialDelay, 2.0, Duration.ofSeconds(30));
  }

  static BackoffStrategy exponential(Duration initialDelay, double multiplier, Duration maxDelay) {
    return new Exponential(initialDelay, multiplier, maxDelay);
  }

  static BackoffStrategy exponentialWithJitter(Duration initialDelay) {
    return new ExponentialWithJitter(initialDelay, 2.0, Duration.ofSeconds(30));
  }

  static BackoffStrategy exponentialWithJitter(Duration initialDelay, double multiplier, Duration maxDelay) {
    return new ExponentialWithJitter(initialDelay, multiplier, maxDelay);
  }

  static BackoffStrategy decorrelatedJitter(Duration initialDelay) {
    return new DecorrelatedJitter(initialDelay, Duration.ofSeconds(30));
  }

  static BackoffStrategy decorrelatedJitter(Duration initialDelay, Duration maxDelay) {
    return new DecorrelatedJitter(initialDelay, maxDelay);
  }

  static BackoffStrategy noWait() {
    return new NoWait();
  }

  static BackoffStrategy custom(IntFunction<Duration> delayFunction) {
    return new Custom(delayFunction);
  }

  // ======================== Core interface ========================

  /**
   * Computes the delay before the given retry attempt.
   *
   * <p>Stateless strategies should override this method. The default
   * implementation of {@link #computeDelay(int, Duration)} delegates here,
   * ignoring the previous delay.
   *
   * @param attemptIndex zero-based retry index (0 = first retry after initial failure)
   * @return the duration to wait
   */
  Duration computeDelay(int attemptIndex);

  /**
   * Computes the delay before the given retry attempt, with access to
   * the previous delay for stateful strategies.
   *
   * <p>The default implementation ignores {@code previousDelay} and delegates
   * to {@link #computeDelay(int)}. Stateful strategies (e.g.,
   * {@link DecorrelatedJitter}) override this method.
   *
   * <p>The retry core always calls this two-argument form, passing the
   * delay from the previous retry cycle (or {@link Duration#ZERO} for
   * the first retry).
   *
   * @param attemptIndex  zero-based retry index
   * @param previousDelay the delay used for the previous retry, or {@link Duration#ZERO}
   * @return the duration to wait
   */
  default Duration computeDelay(int attemptIndex, Duration previousDelay) {
    return computeDelay(attemptIndex);
  }

  // ======================== Implementations ========================

  /**
   * Constant wait duration between every retry attempt.
   *
   * @param delay the fixed delay between attempts
   */
  record Fixed(Duration delay) implements BackoffStrategy {

    public Fixed {
      Objects.requireNonNull(delay, "delay must not be null");
      if (delay.isNegative()) {
        throw new IllegalArgumentException("delay must not be negative");
      }
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      return delay;
    }
  }

  /**
   * Linearly increasing wait duration: {@code initialDelay + attemptIndex * increment},
   * capped at {@code maxDelay}.
   *
   * <p>Growth is additive rather than multiplicative — suitable for scenarios
   * where exponential backoff is too aggressive, e.g., database connections
   * that recover quickly after a brief outage.
   *
   * <p>Example with initialDelay=1s, increment=500ms:
   * 1.0s → 1.5s → 2.0s → 2.5s → 3.0s → ...
   *
   * @param initialDelay the delay before the first retry
   * @param increment    added to the delay for each subsequent retry
   * @param maxDelay     ceiling for the computed delay
   */
  record Linear(Duration initialDelay, Duration increment, Duration maxDelay) implements BackoffStrategy {

    public Linear {
      Objects.requireNonNull(initialDelay, "initialDelay must not be null");
      Objects.requireNonNull(increment, "increment must not be null");
      Objects.requireNonNull(maxDelay, "maxDelay must not be null");
      if (initialDelay.isNegative()) {
        throw new IllegalArgumentException("initialDelay must not be negative");
      }
      if (increment.isNegative()) {
        throw new IllegalArgumentException("increment must not be negative");
      }
      if (maxDelay.isNegative() || maxDelay.isZero()) {
        throw new IllegalArgumentException("maxDelay must be positive");
      }
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      // Use nanoseconds for sub-millisecond precision
      long delayNanos = initialDelay.toNanos() + (long) attemptIndex * increment.toNanos();

      // Guard against overflow: if the addition wrapped around or exceeds max
      if (delayNanos < 0 || delayNanos > maxDelay.toNanos()) {
        return maxDelay;
      }

      return Duration.ofNanos(delayNanos);
    }
  }

  /**
   * Fibonacci-sequence backoff: {@code initialDelay * fib(attemptIndex + 1)},
   * capped at {@code maxDelay}.
   *
   * <p>Grows at approximately φ^n (≈ 1.618^n) — faster than linear but gentler
   * than exponential (2^n). The natural growth rate makes it a good middle ground.
   *
   * <p>Example with initialDelay=1s:
   * 1s → 1s → 2s → 3s → 5s → 8s → 13s → 21s → 30s (capped)
   *
   * @param initialDelay the base unit multiplied by the Fibonacci number
   * @param maxDelay     ceiling for the computed delay
   */
  record Fibonacci(Duration initialDelay, Duration maxDelay) implements BackoffStrategy {

    public Fibonacci {
      Objects.requireNonNull(initialDelay, "initialDelay must not be null");
      Objects.requireNonNull(maxDelay, "maxDelay must not be null");
      if (initialDelay.isNegative() || initialDelay.isZero()) {
        throw new IllegalArgumentException("initialDelay must be positive");
      }
      if (maxDelay.isNegative() || maxDelay.isZero()) {
        throw new IllegalArgumentException("maxDelay must be positive");
      }
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      long fibValue = computeFibonacci(attemptIndex + 1);
      long delayNanos = initialDelay.toNanos() * fibValue;
      long maxNanos = maxDelay.toNanos();

      // Guard against overflow or exceeding max
      if (delayNanos < 0 || delayNanos > maxNanos || fibValue < 0) {
        return maxDelay;
      }

      return Duration.ofNanos(delayNanos);
    }

    /**
     * Computes the n-th Fibonacci number iteratively.
     * fib(0)=1, fib(1)=1, fib(2)=2, fib(3)=3, fib(4)=5, ...
     * Caps at Long.MAX_VALUE on overflow.
     */
    private static long computeFibonacci(int n) {
      if (n <= 1) return 1;

      long prev = 1;
      long curr = 1;
      for (int i = 2; i <= n; i++) {
        long next = prev + curr;
        if (next < 0) return Long.MAX_VALUE; // overflow
        prev = curr;
        curr = next;
      }
      return curr;
    }
  }

  /**
   * Exponentially increasing wait duration: {@code initialDelay * multiplier^attemptIndex},
   * capped at {@code maxDelay}.
   *
   * @param initialDelay the delay before the first retry
   * @param multiplier   the factor by which delay grows each attempt
   * @param maxDelay     ceiling for the computed delay
   */
  record Exponential(Duration initialDelay, double multiplier, Duration maxDelay) implements BackoffStrategy {

    public Exponential {
      Objects.requireNonNull(initialDelay, "initialDelay must not be null");
      Objects.requireNonNull(maxDelay, "maxDelay must not be null");
      if (initialDelay.isNegative() || initialDelay.isZero()) {
        throw new IllegalArgumentException("initialDelay must be positive");
      }
      if (multiplier < 1.0) {
        throw new IllegalArgumentException("multiplier must be >= 1.0, got " + multiplier);
      }
      if (maxDelay.isNegative() || maxDelay.isZero()) {
        throw new IllegalArgumentException("maxDelay must be positive");
      }
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      double delayNanos = initialDelay.toNanos() * Math.pow(multiplier, attemptIndex);
      long maxNanos = maxDelay.toNanos();

      if (Double.isInfinite(delayNanos) || Double.isNaN(delayNanos) || delayNanos >= maxNanos) {
        return maxDelay;
      }

      return Duration.ofNanos((long) delayNanos);
    }
  }

  /**
   * Exponential backoff with full jitter. The actual delay is uniformly
   * distributed in {@code [0, computedExponentialDelay]}.
   *
   * @param initialDelay the base delay before the first retry
   * @param multiplier   the factor by which delay grows each attempt
   * @param maxDelay     ceiling for the computed delay (before jitter)
   */
  record ExponentialWithJitter(Duration initialDelay, double multiplier, Duration maxDelay) implements BackoffStrategy {

    public ExponentialWithJitter {
      Objects.requireNonNull(initialDelay, "initialDelay must not be null");
      Objects.requireNonNull(maxDelay, "maxDelay must not be null");
      if (initialDelay.isNegative() || initialDelay.isZero()) {
        throw new IllegalArgumentException("initialDelay must be positive");
      }
      if (multiplier < 1.0) {
        throw new IllegalArgumentException("multiplier must be >= 1.0, got " + multiplier);
      }
      if (maxDelay.isNegative() || maxDelay.isZero()) {
        throw new IllegalArgumentException("maxDelay must be positive");
      }
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      double delayNanos = initialDelay.toNanos() * Math.pow(multiplier, attemptIndex);
      long maxNanos = maxDelay.toNanos();

      long cappedNanos;
      if (Double.isInfinite(delayNanos) || Double.isNaN(delayNanos) || delayNanos >= maxNanos) {
        cappedNanos = maxNanos;
      } else {
        cappedNanos = (long) delayNanos;
      }

      long jitteredNanos = cappedNanos <= 0 ? 0 : ThreadLocalRandom.current().nextLong(cappedNanos + 1);
      return Duration.ofNanos(jitteredNanos);
    }
  }

  /**
   * Decorrelated Jitter — the AWS-recommended backoff strategy for distributed systems.
   *
   * <p>Unlike full jitter which randomises within a deterministic exponential
   * envelope, decorrelated jitter computes:
   * <pre>
   *   delay = random_between(initialDelay, previousDelay * 3)
   *   delay = min(delay, maxDelay)
   * </pre>
   *
   * <p>The key insight is that each client's delay sequence diverges rapidly
   * because it depends on the <em>previous delay</em> (which was itself random),
   * not on a shared exponential curve. This produces better spread between
   * competing clients than full jitter.
   *
   * <p>This is a <strong>stateful</strong> strategy — it overrides
   * {@link #computeDelay(int, Duration)} to receive the previous delay.
   * On the first retry ({@code previousDelay == ZERO}), it uses
   * {@code initialDelay} as the baseline.
   *
   * @param initialDelay the minimum delay and baseline for the first retry
   * @param maxDelay     ceiling for the computed delay
   * @see <a href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">
   *      AWS Architecture Blog: Exponential Backoff and Jitter</a>
   */
  record DecorrelatedJitter(Duration initialDelay, Duration maxDelay) implements BackoffStrategy {

    public DecorrelatedJitter {
      Objects.requireNonNull(initialDelay, "initialDelay must not be null");
      Objects.requireNonNull(maxDelay, "maxDelay must not be null");
      if (initialDelay.isNegative() || initialDelay.isZero()) {
        throw new IllegalArgumentException("initialDelay must be positive");
      }
      if (maxDelay.isNegative() || maxDelay.isZero()) {
        throw new IllegalArgumentException("maxDelay must be positive");
      }
    }

    /**
     * Stateless fallback — uses initialDelay as the previous delay baseline.
     * The retry core calls the two-argument form; this exists for direct usage.
     */
    @Override
    public Duration computeDelay(int attemptIndex) {
      return computeDelay(attemptIndex, Duration.ZERO);
    }

    /**
     * Computes the decorrelated jitter delay based on the previous delay.
     *
     * @param attemptIndex  ignored (delay depends on previousDelay, not index)
     * @param previousDelay the delay from the previous retry cycle
     * @return a random duration in {@code [initialDelay, min(maxDelay, previousDelay * 3)]}
     */
    @Override
    public Duration computeDelay(int attemptIndex, Duration previousDelay) {
      long initialNanos = initialDelay.toNanos();
      long maxNanos = maxDelay.toNanos();

      // On the first retry or if previous delay was zero, use initialDelay as baseline
      long baseNanos = (previousDelay == null || previousDelay.isZero())
          ? initialNanos
          : previousDelay.toNanos();

      // Upper bound: previousDelay * 3, capped at maxDelay
      long upperBound = Math.min(maxNanos, baseNanos * 3);

      // Guard against overflow: baseNanos * 3 can overflow for very large delays
      if (upperBound < 0 || baseNanos < 0) {
        upperBound = maxNanos;
      }

      // Ensure lower bound does not exceed upper bound
      long lowerBound = Math.min(initialNanos, upperBound);

      if (lowerBound >= upperBound) {
        return Duration.ofNanos(lowerBound);
      }

      // Random in [lowerBound, upperBound]
      long jitteredNanos = ThreadLocalRandom.current().nextLong(lowerBound, upperBound + 1);
      return Duration.ofNanos(jitteredNanos);
    }
  }

  /**
   * Zero delay — retry immediately without any wait.
   */
  record NoWait() implements BackoffStrategy {

    @Override
    public Duration computeDelay(int attemptIndex) {
      return Duration.ZERO;
    }
  }

  /**
   * User-defined delay function for full control without subclassing.
   *
   * <p>The function receives the zero-based attempt index and returns the
   * delay duration. Use this for one-off or application-specific backoff
   * patterns that don't warrant a dedicated strategy class.
   *
   * <p>Example:
   * <pre>{@code
   *   // Delay from a lookup table
   *   Duration[] delays = {Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)};
   *   BackoffStrategy.custom(i -> delays[Math.min(i, delays.length - 1)]);
   * }</pre>
   *
   * @param delayFunction maps a zero-based attempt index to a delay duration
   */
  record Custom(IntFunction<Duration> delayFunction) implements BackoffStrategy {

    public Custom {
      Objects.requireNonNull(delayFunction, "delayFunction must not be null");
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      Duration delay = delayFunction.apply(attemptIndex);
      Objects.requireNonNull(delay,
          "delayFunction must not return null for attemptIndex " + attemptIndex);
      if (delay.isNegative()) {
        throw new IllegalArgumentException(
            "delayFunction returned negative delay for attemptIndex %d: %s"
                .formatted(attemptIndex, delay));
      }
      return delay;
    }
  }
}
