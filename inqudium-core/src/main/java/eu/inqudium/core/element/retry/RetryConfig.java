package eu.inqudium.core.element.retry;

import eu.inqudium.core.element.retry.strategy.BackoffStrategy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Immutable configuration for a retry instance.
 *
 * <p>Use {@link #builder(String)} to construct.
 *
 * @param name            a human-readable identifier (used in exceptions and events)
 * @param maxAttempts     maximum number of attempts (initial call + retries);
 *                        e.g. 3 means 1 initial call + 2 retries
 * @param backoffStrategy the strategy for computing wait durations between retries
 * @param retryPredicate  predicate that decides whether a given throwable should
 *                        trigger a retry ({@code true} = retry)
 * @param resultPredicate predicate that decides whether a given result should
 *                        trigger a retry ({@code true} = retry); may be {@code null}
 */
public record RetryConfig(
        String name,
        int maxAttempts,
        BackoffStrategy backoffStrategy,
        Predicate<Throwable> retryPredicate,
        Predicate<Object> resultPredicate
) {

    public RetryConfig {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(backoffStrategy, "backoffStrategy must not be null");
        Objects.requireNonNull(retryPredicate, "retryPredicate must not be null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Returns the maximum number of retries (maxAttempts - 1).
     */
    public int maxRetries() {
        return maxAttempts - 1;
    }

    /**
     * Checks whether the given throwable should trigger a retry.
     */
    public boolean shouldRetryOnException(Throwable throwable) {
        return retryPredicate.test(throwable);
    }

    /**
     * Checks whether the given result should trigger a retry.
     *
     * <p><strong>Fix 5:</strong> Wraps the predicate evaluation in a try-catch to
     * produce a clear error message when the result type does not match the
     * predicate's expected type. Without this, a {@link ClassCastException}
     * would surface deep in the retry loop with no indication of the root cause.
     */
    public boolean shouldRetryOnResult(Object result) {
        if (resultPredicate == null) {
            return false;
        }
        try {
            return resultPredicate.test(result);
        } catch (ClassCastException e) {
            throw new ClassCastException(
                    "Result predicate type mismatch in retry '%s': the predicate cannot evaluate a result of type %s. "
                            .formatted(name, result == null ? "null" : result.getClass().getName())
                            + "Ensure the type parameter of retryOnResult() matches the callable's return type. "
                            + "Original: " + e.getMessage());
        }
    }

    public static final class Builder {
        private final String name;
        private int maxAttempts = 3;
        private BackoffStrategy backoffStrategy = BackoffStrategy.fixedDelay(Duration.ofMillis(500));
        private Predicate<Throwable> retryPredicate = e -> true;
        private Predicate<Object> resultPredicate = null;
        private PredicateSource predicateSource = PredicateSource.NONE;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder backoffStrategy(BackoffStrategy backoffStrategy) {
            this.backoffStrategy = backoffStrategy;
            return this;
        }

        public Builder fixedDelay(Duration delay) {
            this.backoffStrategy = BackoffStrategy.fixedDelay(delay);
            return this;
        }

        public Builder exponentialBackoff(Duration initialDelay) {
            this.backoffStrategy = BackoffStrategy.exponential(initialDelay);
            return this;
        }

        public Builder exponentialBackoff(Duration initialDelay, double multiplier, Duration maxDelay) {
            this.backoffStrategy = BackoffStrategy.exponential(initialDelay, multiplier, maxDelay);
            return this;
        }

        public Builder exponentialBackoffWithJitter(Duration initialDelay) {
            this.backoffStrategy = BackoffStrategy.exponentialWithJitter(initialDelay);
            return this;
        }

        public Builder linearBackoff(Duration initialDelay, Duration increment) {
            this.backoffStrategy = BackoffStrategy.linear(initialDelay, increment);
            return this;
        }

        public Builder linearBackoff(Duration initialDelay, Duration increment, Duration maxDelay) {
            this.backoffStrategy = BackoffStrategy.linear(initialDelay, increment, maxDelay);
            return this;
        }

        public Builder fibonacciBackoff(Duration initialDelay) {
            this.backoffStrategy = BackoffStrategy.fibonacci(initialDelay);
            return this;
        }

        public Builder fibonacciBackoff(Duration initialDelay, Duration maxDelay) {
            this.backoffStrategy = BackoffStrategy.fibonacci(initialDelay, maxDelay);
            return this;
        }

        public Builder decorrelatedJitter(Duration initialDelay) {
            this.backoffStrategy = BackoffStrategy.decorrelatedJitter(initialDelay);
            return this;
        }

        public Builder decorrelatedJitter(Duration initialDelay, Duration maxDelay) {
            this.backoffStrategy = BackoffStrategy.decorrelatedJitter(initialDelay, maxDelay);
            return this;
        }

        public Builder noWait() {
            this.backoffStrategy = BackoffStrategy.noWait();
            return this;
        }

        /**
         * Sets the retry predicate directly.
         *
         * <p>Cannot be used after {@link #retryOnExceptions} or {@link #ignoreExceptions}
         * has already been called.
         */
        public Builder retryPredicate(Predicate<Throwable> retryPredicate) {
            if (predicateSource == PredicateSource.RETRY_ON_EXCEPTIONS
                    || predicateSource == PredicateSource.IGNORE_EXCEPTIONS) {
                throw new IllegalStateException(
                        "Cannot use retryPredicate() after %s was already called."
                                .formatted(predicateSource));
            }
            this.retryPredicate = retryPredicate;
            this.predicateSource = PredicateSource.RAW;
            return this;
        }

        /**
         * Only retry on the specified exception types.
         *
         * <p>Cannot be combined with {@link #ignoreExceptions} or called after any
         * other predicate configuration method.
         */
        @SafeVarargs
        public final Builder retryOnExceptions(Class<? extends Throwable>... exceptionTypes) {
            if (predicateSource != PredicateSource.NONE) {
                throw new IllegalStateException(
                        "retryOnExceptions() cannot be combined with other predicate configuration methods. "
                                + "Already configured via: " + predicateSource);
            }
            this.retryPredicate = throwable -> {
                for (Class<? extends Throwable> type : exceptionTypes) {
                    if (type.isInstance(throwable)) {
                        return true;
                    }
                }
                return false;
            };
            this.predicateSource = PredicateSource.RETRY_ON_EXCEPTIONS;
            return this;
        }

        /**
         * Do not retry on the specified exception types.
         *
         * <p>Cannot be combined with {@link #retryOnExceptions} or called after any
         * other predicate configuration method.
         */
        @SafeVarargs
        public final Builder ignoreExceptions(Class<? extends Throwable>... exceptionTypes) {
            if (predicateSource != PredicateSource.NONE) {
                throw new IllegalStateException(
                        "ignoreExceptions() cannot be combined with other predicate configuration methods. "
                                + "Already configured via: " + predicateSource);
            }
            this.retryPredicate = throwable -> {
                for (Class<? extends Throwable> type : exceptionTypes) {
                    if (type.isInstance(throwable)) {
                        return false;
                    }
                }
                return true;
            };
            this.predicateSource = PredicateSource.IGNORE_EXCEPTIONS;
            return this;
        }

        /**
         * Retry when the result satisfies the given predicate.
         * Useful for retrying on specific return values (e.g. null, empty).
         *
         * <p><strong>Type safety caveat:</strong> Because {@code RetryConfig} is not
         * type-parameterized, there is no compile-time guarantee that the predicate's
         * type parameter matches the callable's return type. A {@link ClassCastException}
         * at runtime is caught and re-thrown with a descriptive message by
         * {@link RetryConfig#shouldRetryOnResult}.
         */
        @SuppressWarnings("unchecked")
        public <T> Builder retryOnResult(Predicate<T> resultPredicate) {
            this.resultPredicate = (Predicate<Object>) resultPredicate;
            return this;
        }

        public RetryConfig build() {
            return new RetryConfig(name, maxAttempts, backoffStrategy, retryPredicate, resultPredicate);
        }

        // Fix 6: Hardened predicate source tracking (same pattern as CircuitBreakerConfig)
        private enum PredicateSource {NONE, RAW, RETRY_ON_EXCEPTIONS, IGNORE_EXCEPTIONS}
    }
}
