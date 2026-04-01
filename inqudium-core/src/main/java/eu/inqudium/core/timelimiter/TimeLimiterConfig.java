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
    return exceptionFactory.apply(name, timeout);
  }

  /**
   * Creates the timeout exception for the given effective timeout.
   *
   * <p>Fix 4/6: When an overridden timeout is used (via {@code execute(callable, timeout)}),
   * the exception should reflect the actual timeout, not the configured default.
   *
   * @param effectiveTimeout the actual timeout that was exceeded
   * @return the timeout exception
   */
  public RuntimeException createTimeoutException(Duration effectiveTimeout) {
    return exceptionFactory.apply(name, effectiveTimeout);
  }

  public static final class Builder {
    private final String name;
    private Duration timeout = Duration.ofSeconds(5);
    private boolean cancelOnTimeout = true;
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
