package eu.inqudium.core.element.ratelimiter;

import eu.inqudium.core.element.ratelimiter.strategy.RateLimiterStrategy;
import eu.inqudium.core.element.ratelimiter.strategy.TokenBucketState;
import eu.inqudium.core.element.ratelimiter.strategy.TokenBucketStrategy;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a rate limiter instance.
 *
 * @param name           a human-readable identifier (used in exceptions and events)
 * @param capacity       maximum number of permits in the bucket / window
 * @param refillPermits  how many permits are added per refill cycle
 * @param refillPeriod   duration of one refill cycle / window size
 * @param defaultTimeout how long callers may wait for a permit (zero = fail-fast)
 * @param strategy       the underlying rate limiting algorithm
 */
public record RateLimiterConfig<S extends RateLimiterState>(
        String name,
        int capacity,
        int refillPermits,
        Duration refillPeriod,
        Duration defaultTimeout,
        RateLimiterStrategy<S> strategy
) {

    public RateLimiterConfig {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(refillPeriod, "refillPeriod must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        if (refillPermits < 1) {
            throw new IllegalArgumentException("refillPermits must be >= 1, got " + refillPermits);
        }
        if (refillPeriod.isNegative() || refillPeriod.isZero()) {
            throw new IllegalArgumentException("refillPeriod must be positive");
        }
        if (defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("defaultTimeout must not be negative");
        }
    }

    /**
     * Creates a builder with the default Token Bucket algorithm.
     */
    public static Builder<TokenBucketState> builder(String name) {
        return new Builder<>(name, new TokenBucketStrategy());
    }

    // Fix 10: Removed nanosPerPermit() — it was unused and accumulated
    // rounding errors with non-divisible refillPermits values.
    // If needed in the future, use integer-only arithmetic:
    //   long nanosPerPermit = refillPeriod.toNanos() / refillPermits;

    public static final class Builder<S extends RateLimiterState> {
        private final String name;
        private final RateLimiterStrategy<S> strategy;
        private int capacity = 10;
        private int refillPermits = 10;
        private Duration refillPeriod = Duration.ofSeconds(1);
        private Duration defaultTimeout = Duration.ZERO;

        private Builder(String name, RateLimiterStrategy<S> strategy) {
            this.name = Objects.requireNonNull(name);
            this.strategy = strategy;
        }

        public Builder<S> capacity(int capacity) {
            this.capacity = capacity;
            return this;
        }

        public Builder<S> refillPermits(int refillPermits) {
            this.refillPermits = refillPermits;
            return this;
        }

        public Builder<S> refillPeriod(Duration refillPeriod) {
            this.refillPeriod = refillPeriod;
            return this;
        }

        public Builder<S> defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        public Builder<S> limitForPeriod(int permits, Duration period) {
            this.capacity = permits;
            this.refillPermits = permits;
            this.refillPeriod = period;
            return this;
        }

        /**
         * Switches the underlying algorithm.
         * Changes the builder's type parameter to the new strategy's state type.
         */
        public <T extends RateLimiterState> Builder<T> withStrategy(RateLimiterStrategy<T> newStrategy) {
            Builder<T> newBuilder = new Builder<>(this.name, Objects.requireNonNull(newStrategy));
            newBuilder.capacity = this.capacity;
            newBuilder.refillPermits = this.refillPermits;
            newBuilder.refillPeriod = this.refillPeriod;
            newBuilder.defaultTimeout = this.defaultTimeout;
            return newBuilder;
        }

        public RateLimiterConfig<S> build() {
            return new RateLimiterConfig<>(name, capacity, refillPermits, refillPeriod, defaultTimeout, strategy);
        }
    }
}
