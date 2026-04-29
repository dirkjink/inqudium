package eu.inqudium.core.element.trafficshaper;

import eu.inqudium.core.element.trafficshaper.strategy.LeakyBucketState;
import eu.inqudium.core.element.trafficshaper.strategy.LeakyBucketStrategy;
import eu.inqudium.core.element.trafficshaper.strategy.SchedulingState;
import eu.inqudium.core.element.trafficshaper.strategy.SchedulingStrategy;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a traffic shaper instance.
 *
 * <p>The traffic shaper uses a pluggable {@link SchedulingStrategy} to
 * assign time slots to incoming requests. The default strategy is
 * {@link LeakyBucketStrategy}, which produces evenly-spaced output
 * traffic by assigning successive slots spaced {@link #interval()} apart.
 *
 * @param <S> the strategy-specific state type
 */
public record TrafficShaperConfig<S extends SchedulingState>(
        String name,
        double ratePerSecond,
        Duration interval,
        int maxQueueDepth,
        Duration maxWaitDuration,
        ThrottleMode throttleMode,
        Duration unboundedWarnAfter,
        SchedulingStrategy<S> strategy
) {

    public TrafficShaperConfig {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        Objects.requireNonNull(maxWaitDuration, "maxWaitDuration must not be null");
        Objects.requireNonNull(throttleMode, "throttleMode must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be positive, got " + ratePerSecond);
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (maxQueueDepth < -1) {
            throw new IllegalArgumentException("maxQueueDepth must be >= -1, got " + maxQueueDepth);
        }
        if (maxWaitDuration.isNegative()) {
            throw new IllegalArgumentException("maxWaitDuration must not be negative");
        }
    }

    /**
     * Creates a builder with the default Leaky Bucket strategy.
     */
    public static Builder<LeakyBucketState> builder(String name) {
        return new Builder<>(name, new LeakyBucketStrategy());
    }

    public boolean isQueuingAllowed() {
        return maxQueueDepth != 0;
    }

    public boolean hasQueueDepthLimit() {
        return maxQueueDepth > 0;
    }

    public boolean hasMaxWaitDurationLimit() {
        return !maxWaitDuration.isZero();
    }

    public static final class Builder<S extends SchedulingState> {
        private final String name;
        private final SchedulingStrategy<S> strategy;
        private double ratePerSecond = 10.0;
        private int maxQueueDepth = 50;
        private Duration maxWaitDuration = Duration.ofSeconds(10);
        private ThrottleMode throttleMode = ThrottleMode.SHAPE_AND_REJECT_OVERFLOW;
        private Duration unboundedWarnAfter = Duration.ofMinutes(1);
        private Integer rawCount = null;
        private Duration rawPeriod = null;

        private Builder(String name, SchedulingStrategy<S> strategy) {
            this.name = Objects.requireNonNull(name);
            this.strategy = strategy;
        }

        public Builder<S> ratePerSecond(double ratePerSecond) {
            this.ratePerSecond = ratePerSecond;
            this.rawCount = null;
            this.rawPeriod = null;
            return this;
        }

        public Builder<S> rateForPeriod(int count, Duration period) {
            if (count < 1) throw new IllegalArgumentException("count must be >= 1, got " + count);
            Objects.requireNonNull(period, "period must not be null");
            if (period.isNegative() || period.isZero()) throw new IllegalArgumentException("period must be positive");
            this.ratePerSecond = (double) count / ((double) period.toNanos() / 1_000_000_000.0);
            this.rawCount = count;
            this.rawPeriod = period;
            return this;
        }

        public Builder<S> maxQueueDepth(int maxQueueDepth) {
            this.maxQueueDepth = maxQueueDepth;
            return this;
        }

        public Builder<S> maxWaitDuration(Duration d) {
            this.maxWaitDuration = d;
            return this;
        }

        public Builder<S> throttleMode(ThrottleMode m) {
            this.throttleMode = m;
            return this;
        }

        public Builder<S> unboundedWarnAfter(Duration d) {
            this.unboundedWarnAfter = d;
            return this;
        }

        /**
         * Switches the underlying scheduling algorithm.
         * Changes the builder's type parameter to the new strategy's state type.
         */
        public <T extends SchedulingState> Builder<T> withStrategy(SchedulingStrategy<T> newStrategy) {
            Builder<T> b = new Builder<>(this.name, Objects.requireNonNull(newStrategy));
            b.ratePerSecond = this.ratePerSecond;
            b.maxQueueDepth = this.maxQueueDepth;
            b.maxWaitDuration = this.maxWaitDuration;
            b.throttleMode = this.throttleMode;
            b.unboundedWarnAfter = this.unboundedWarnAfter;
            b.rawCount = this.rawCount;
            b.rawPeriod = this.rawPeriod;
            return b;
        }

        public TrafficShaperConfig<S> build() {
            long intervalNanos;
            if (rawCount != null && rawPeriod != null) {
                intervalNanos = rawPeriod.toNanos() / rawCount;
            } else {
                intervalNanos = (long) (1_000_000_000.0 / ratePerSecond);
            }
            long minimumIntervalNanos = 1_000; // 1 microsecond
            if (intervalNanos < minimumIntervalNanos) intervalNanos = minimumIntervalNanos;

            return new TrafficShaperConfig<>(
                    name, ratePerSecond, Duration.ofNanos(intervalNanos), maxQueueDepth, maxWaitDuration,
                    throttleMode, unboundedWarnAfter, strategy);
        }
    }
}
