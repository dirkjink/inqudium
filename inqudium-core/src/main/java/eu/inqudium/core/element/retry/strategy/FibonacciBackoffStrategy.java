package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;
import java.util.Objects;

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
public record FibonacciBackoffStrategy(Duration initialDelay, Duration maxDelay) implements BackoffStrategy {

  public FibonacciBackoffStrategy {
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
}
