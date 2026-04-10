package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter. The actual delay is uniformly
 * distributed in {@code [0, computedExponentialDelay]}.
 *
 * @param initialDelay the base delay before the first retry
 * @param multiplier   the factor by which delay grows each attempt
 * @param maxDelay     ceiling for the computed delay (before jitter)
 */
public record ExponentialWithJitterBackoffStrategy(Duration initialDelay, double multiplier,
                                                   Duration maxDelay) implements BackoffStrategy {

    public ExponentialWithJitterBackoffStrategy {
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
