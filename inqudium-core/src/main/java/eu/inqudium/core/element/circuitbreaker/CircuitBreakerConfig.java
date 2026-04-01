package eu.inqudium.core.element.circuitbreaker;

import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedErrorRateMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable configuration for a circuit breaker instance.
 * Use {@link #builder(String)} to construct.
 */
public record CircuitBreakerConfig(
    String name,
    int failureThreshold,
    int successThresholdInHalfOpen,
    int permittedCallsInHalfOpen,
    Duration waitDurationInOpenState,
    Predicate<Throwable> recordFailurePredicate,
    Function<Instant, FailureMetrics> metricsFactory
) {

  public CircuitBreakerConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(waitDurationInOpenState, "waitDurationInOpenState must not be null");
    Objects.requireNonNull(recordFailurePredicate, "recordFailurePredicate must not be null");
    Objects.requireNonNull(metricsFactory, "metricsFactory must not be null");

    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold must be >= 1, got " + failureThreshold);
    }
    if (successThresholdInHalfOpen < 1) {
      throw new IllegalArgumentException("successThresholdInHalfOpen must be >= 1, got " + successThresholdInHalfOpen);
    }
    if (permittedCallsInHalfOpen < 1) {
      throw new IllegalArgumentException("permittedCallsInHalfOpen must be >= 1, got " + permittedCallsInHalfOpen);
    }
    if (permittedCallsInHalfOpen < successThresholdInHalfOpen) {
      throw new IllegalArgumentException(
          "permittedCallsInHalfOpen (%d) must be >= successThresholdInHalfOpen (%d), otherwise the circuit can never transition from HALF_OPEN back to CLOSED"
              .formatted(permittedCallsInHalfOpen, successThresholdInHalfOpen));
    }
    if (waitDurationInOpenState.isNegative() || waitDurationInOpenState.isZero()) {
      throw new IllegalArgumentException("waitDurationInOpenState must be positive");
    }
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public boolean shouldRecordAsFailure(Throwable throwable) {
    return recordFailurePredicate.test(throwable);
  }

  public static final class Builder {
    private final String name;

    private int failureThreshold = 50; // 50% failure rate
    private int successThresholdInHalfOpen = 3;
    private int permittedCallsInHalfOpen = 3;
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);

    // ======================== Fix 2a: Default predicate excludes Errors ========================
    // Only Exceptions indicate downstream failures. JVM-level Errors (OOM, StackOverflow)
    // should not trip the circuit breaker.
    private Predicate<Throwable> recordFailurePredicate = e -> e instanceof Exception;
    private PredicateSource predicateSource = PredicateSource.NONE;
    // Settings for the default metric strategy
    private int slidingWindowSeconds = 10;
    private int minimumNumberOfCalls = 10;
    // Allows overriding the metric strategy
    private Function<Instant, FailureMetrics> customMetricsFactory = null;

    private Builder(String name) {
      this.name = Objects.requireNonNull(name);
    }

    public Builder failureThreshold(int failureThreshold) {
      this.failureThreshold = failureThreshold;
      return this;
    }

    public Builder successThresholdInHalfOpen(int successThresholdInHalfOpen) {
      this.successThresholdInHalfOpen = successThresholdInHalfOpen;
      return this;
    }

    public Builder permittedCallsInHalfOpen(int permittedCallsInHalfOpen) {
      this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
      return this;
    }

    public Builder waitDurationInOpenState(Duration waitDurationInOpenState) {
      this.waitDurationInOpenState = waitDurationInOpenState;
      return this;
    }

    /**
     * Set the size of the time-based sliding window.
     * Only applies if no custom metrics factory is provided.
     */
    public Builder slidingWindow(Duration windowSize) {
      this.slidingWindowSeconds = (int) Math.max(1, windowSize.getSeconds());
      return this;
    }

    /**
     * Set the minimum number of calls required before the failure rate is evaluated.
     * Only applies if no custom metrics factory is provided.
     */
    public Builder minimumNumberOfCalls(int minimumNumberOfCalls) {
      this.minimumNumberOfCalls = minimumNumberOfCalls;
      return this;
    }

    /**
     * Provide a custom strategy for tracking failures.
     * Overrides the default Time-Based Error Rate algorithm.
     */
    public Builder metricsStrategy(Function<Instant, FailureMetrics> factory) {
      this.customMetricsFactory = Objects.requireNonNull(factory);
      return this;
    }

    /**
     * Set a custom predicate for determining which throwables count as failures.
     *
     * <p>Cannot be used after {@link #recordExceptions} or {@link #ignoreExceptions}
     * has already been called.
     */
    public Builder recordFailurePredicate(Predicate<Throwable> recordFailurePredicate) {
      if (predicateSource == PredicateSource.RECORD_EXCEPTIONS
          || predicateSource == PredicateSource.IGNORE_EXCEPTIONS) {
        throw new IllegalStateException(
            "Cannot use recordFailurePredicate() after %s was already called."
                .formatted(predicateSource));
      }
      this.recordFailurePredicate = recordFailurePredicate;
      this.predicateSource = PredicateSource.RAW;
      return this;
    }

    // ======================== Fix 8: Hardened predicate configuration ========================

    /**
     * Only record failures for the specified exception types.
     *
     * <p>Cannot be combined with {@link #ignoreExceptions} or called after any
     * other predicate configuration method.
     */
    @SafeVarargs
    public final Builder recordExceptions(Class<? extends Throwable>... exceptionTypes) {
      if (predicateSource != PredicateSource.NONE) {
        throw new IllegalStateException(
            "recordExceptions() cannot be combined with other predicate configuration methods. "
                + "Already configured via: " + predicateSource);
      }
      this.recordFailurePredicate = throwable -> {
        for (Class<? extends Throwable> type : exceptionTypes) {
          if (type.isInstance(throwable)) return true;
        }
        return false;
      };
      this.predicateSource = PredicateSource.RECORD_EXCEPTIONS;
      return this;
    }

    /**
     * Record all exceptions as failures EXCEPT the specified types.
     *
     * <p>Cannot be combined with {@link #recordExceptions} or called after any
     * other predicate configuration method.
     */
    @SafeVarargs
    public final Builder ignoreExceptions(Class<? extends Throwable>... exceptionTypes) {
      if (predicateSource != PredicateSource.NONE) {
        throw new IllegalStateException(
            "ignoreExceptions() cannot be combined with other predicate configuration methods. "
                + "Already configured via: " + predicateSource);
      }
      this.recordFailurePredicate = throwable -> {
        for (Class<? extends Throwable> type : exceptionTypes) {
          if (type.isInstance(throwable)) return false;
        }
        return true;
      };
      this.predicateSource = PredicateSource.IGNORE_EXCEPTIONS;
      return this;
    }

    public CircuitBreakerConfig build() {
      // If the user has not provided a custom factory, use the default time-based strategy
      Function<Instant, FailureMetrics> factoryToUse = customMetricsFactory != null
          ? customMetricsFactory
          : now -> TimeBasedErrorRateMetrics.initial(slidingWindowSeconds, minimumNumberOfCalls, now);

      return new CircuitBreakerConfig(
          name,
          failureThreshold,
          successThresholdInHalfOpen,
          permittedCallsInHalfOpen,
          waitDurationInOpenState,
          recordFailurePredicate,
          factoryToUse
      );
    }

    // ======================== Fix 8: Hardened predicate source tracking ========================
    private enum PredicateSource {NONE, RAW, RECORD_EXCEPTIONS, IGNORE_EXCEPTIONS}
  }
}
