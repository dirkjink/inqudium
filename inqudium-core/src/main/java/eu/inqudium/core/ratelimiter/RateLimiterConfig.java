package eu.inqudium.core.ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a rate limiter instance.
 *
 * <p>The rate limiter uses a <strong>token bucket</strong> algorithm:
 * <ul>
 *   <li>The bucket holds up to {@link #capacity()} permits.</li>
 *   <li>Every {@link #refillPeriod()}, exactly {@link #refillPermits()} tokens
 *       are added back (up to the capacity ceiling).</li>
 *   <li>Each call consumes one permit. When no permits remain the call
 *       is either rejected or — in the imperative wrapper — optionally
 *       waits up to {@link #defaultTimeout()}.</li>
 * </ul>
 *
 * <p>Use {@link #builder(String)} to construct.
 *
 * @param name            a human-readable identifier (used in exceptions and events)
 * @param capacity        maximum number of permits in the bucket
 * @param refillPermits   how many permits are added per refill cycle
 * @param refillPeriod    duration of one refill cycle
 * @param defaultTimeout  how long callers may wait for a permit (zero = fail-fast)
 */
public record RateLimiterConfig(
        String name,
        int capacity,
        int refillPermits,
        Duration refillPeriod,
        Duration defaultTimeout
) {

    public RateLimiterConfig {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(refillPeriod, "refillPeriod must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
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
     * Returns the duration it takes to refill a single permit.
     * Useful for calculating wait times.
     */
    public Duration nanosPerPermit() {
        long totalNanos = refillPeriod.toNanos();
        long nanosPerPermit = totalNanos / refillPermits;
        return Duration.ofNanos(Math.max(nanosPerPermit, 1));
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private int capacity = 10;
        private int refillPermits = 10;
        private Duration refillPeriod = Duration.ofSeconds(1);
        private Duration defaultTimeout = Duration.ZERO;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        public Builder capacity(int capacity) {
            this.capacity = capacity;
            return this;
        }

        public Builder refillPermits(int refillPermits) {
            this.refillPermits = refillPermits;
            return this;
        }

        public Builder refillPeriod(Duration refillPeriod) {
            this.refillPeriod = refillPeriod;
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        /**
         * Convenience: configures a simple "N requests per period" limiter.
         * Sets both capacity and refillPermits to {@code permits}.
         */
        public Builder limitForPeriod(int permits, Duration period) {
            this.capacity = permits;
            this.refillPermits = permits;
            this.refillPeriod = period;
            return this;
        }

        public RateLimiterConfig build() {
            return new RateLimiterConfig(name, capacity, refillPermits, refillPeriod, defaultTimeout);
        }
    }
}
