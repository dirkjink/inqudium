package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Immutable configuration for a time limiter instance.
 *
 * <p>Controls how long an operation is allowed to run before
 * being considered timed out.
 *
 * <p>Use {@link #builder(String)} to construct.
 *
 * @param name             a human-readable identifier (used in exceptions and events)
 * @param timeout          maximum duration an operation may run
 * @param cancelOnTimeout  whether to cancel/interrupt the running operation on timeout
 * @param exceptionFactory factory for the exception thrown on timeout; receives the
 *                         name and the configured timeout duration and returns the throwable to propagate
 */
public record TimeLimiterConfig(
    String name,
    Duration timeout,
    boolean cancelOnTimeout,
    BiFunction<String, Duration, ? extends RuntimeException> exceptionFactory
) {

  public TimeLimiterConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    Objects.requireNonNull(exceptionFactory, "exceptionFactory must not be null");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive, got " + timeout);
    }
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  /**
   * Creates the timeout exception using the configured factory.
   */
  public RuntimeException createTimeoutException() {
    // Fix 2A: Namen und Dauer sicher injizieren, ohne Exception-Instanzen für instanceof-Checks zu bauen.
    return exceptionFactory.apply(name, timeout);
  }

  public static final class Builder {
    private final String name;
    private Duration timeout = Duration.ofSeconds(5);
    private boolean cancelOnTimeout = true;

    // Fix 2A: BiFunction erwartet Name und Duration. Die Standard-Exception passt perfekt auf diese Signatur.
    private BiFunction<String, Duration, ? extends RuntimeException> exceptionFactory =
        TimeLimiterException::new;

    private Builder(String name) {
      this.name = Objects.requireNonNull(name);
    }

    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    public Builder cancelOnTimeout(boolean cancelOnTimeout) {
      this.cancelOnTimeout = cancelOnTimeout;
      return this;
    }

    /**
     * Custom factory for the exception thrown on timeout.
     * Receives the limiter name and configured timeout duration.
     */
    public Builder exceptionFactory(BiFunction<String, Duration, ? extends RuntimeException> factory) {
      this.exceptionFactory = factory;
      return this;
    }

    public TimeLimiterConfig build() {
      return new TimeLimiterConfig(name, timeout, cancelOnTimeout, exceptionFactory);
    }
  }
}