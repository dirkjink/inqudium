package eu.inqudium.core.circuitbreaker;

import java.time.Duration;
import java.util.Objects;
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
    Predicate<Throwable> recordFailurePredicate
) {

  public CircuitBreakerConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(waitDurationInOpenState, "waitDurationInOpenState must not be null");
    Objects.requireNonNull(recordFailurePredicate, "recordFailurePredicate must not be null");
    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold must be >= 1, got " + failureThreshold);
    }
    if (successThresholdInHalfOpen < 1) {
      throw new IllegalArgumentException("successThresholdInHalfOpen must be >= 1, got " + successThresholdInHalfOpen);
    }
    if (permittedCallsInHalfOpen < 1) {
      throw new IllegalArgumentException("permittedCallsInHalfOpen must be >= 1, got " + permittedCallsInHalfOpen);
    }
    // Fix 1: Prevent impossible configuration where the circuit can never leave HALF_OPEN
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

  /**
   * Checks whether the given throwable should be recorded as a failure.
   */
  public boolean shouldRecordAsFailure(Throwable throwable) {
    return recordFailurePredicate.test(throwable);
  }

  public static final class Builder {
    private final String name;
    private int failureThreshold = 5;
    private int successThresholdInHalfOpen = 3;
    private int permittedCallsInHalfOpen = 3;
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);
    private Predicate<Throwable> recordFailurePredicate = e -> true;

    // Fix 8: Track whether predicate was set via recordExceptions/ignoreExceptions
    // to prevent silent overwriting
    private boolean predicateSetViaConvenienceMethod = false;

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

    public Builder recordFailurePredicate(Predicate<Throwable> recordFailurePredicate) {
      // Fix 8: Direct predicate setting resets the convenience method flag
      this.recordFailurePredicate = recordFailurePredicate;
      this.predicateSetViaConvenienceMethod = false;
      return this;
    }

    /**
     * Convenience method: only record exceptions of the given types as failures.
     *
     * <p>Cannot be combined with {@link #ignoreExceptions} — an
     * {@link IllegalStateException} is thrown if both are called on the same builder.
     */
    @SafeVarargs
    public final Builder recordExceptions(Class<? extends Throwable>... exceptionTypes) {
      // Fix 8: Prevent silent overwriting when combined with ignoreExceptions
      if (predicateSetViaConvenienceMethod) {
        throw new IllegalStateException(
            "recordExceptions() and ignoreExceptions() cannot both be used on the same builder. "
                + "Use recordFailurePredicate() for complex filtering logic.");
      }
      this.recordFailurePredicate = throwable -> {
        for (Class<? extends Throwable> type : exceptionTypes) {
          if (type.isInstance(throwable)) {
            return true;
          }
        }
        return false;
      };
      this.predicateSetViaConvenienceMethod = true;
      return this;
    }

    /**
     * Convenience method: ignore (do not record) exceptions of the given types.
     *
     * <p>Cannot be combined with {@link #recordExceptions} — an
     * {@link IllegalStateException} is thrown if both are called on the same builder.
     */
    @SafeVarargs
    public final Builder ignoreExceptions(Class<? extends Throwable>... exceptionTypes) {
      // Fix 8: Prevent silent overwriting when combined with recordExceptions
      if (predicateSetViaConvenienceMethod) {
        throw new IllegalStateException(
            "recordExceptions() and ignoreExceptions() cannot both be used on the same builder. "
                + "Use recordFailurePredicate() for complex filtering logic.");
      }
      this.recordFailurePredicate = throwable -> {
        for (Class<? extends Throwable> type : exceptionTypes) {
          if (type.isInstance(throwable)) {
            return false;
          }
        }
        return true;
      };
      this.predicateSetViaConvenienceMethod = true;
      return this;
    }

    public CircuitBreakerConfig build() {
      return new CircuitBreakerConfig(
          name,
          failureThreshold,
          successThresholdInHalfOpen,
          permittedCallsInHalfOpen,
          waitDurationInOpenState,
          recordFailurePredicate
      );
    }
  }
}
