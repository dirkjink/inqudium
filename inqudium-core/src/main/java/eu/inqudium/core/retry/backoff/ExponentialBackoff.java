package eu.inqudium.core.retry.backoff;

import java.time.Duration;

/**
 * Exponential backoff: each retry waits exponentially longer.
 *
 * <p>{@code delay = initialInterval × multiplier^(attemptNumber - 1)}
 *
 * <p>With defaults (multiplier=2, initialInterval=500ms):
 * attempt 1 → 500ms, attempt 2 → 1s, attempt 3 → 2s, attempt 4 → 4s.
 *
 * @since 0.1.0
 */
public final class ExponentialBackoff implements BackoffStrategy {

    private final double multiplier;

    /** Creates an exponential backoff with the default multiplier of 2.0. */
    public ExponentialBackoff() {
        this(2.0);
    }

    /**
     * Creates an exponential backoff with a custom multiplier.
     *
     * @param multiplier the growth factor per attempt (must be >= 1.0)
     */
    public ExponentialBackoff(double multiplier) {
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("Multiplier must be >= 1.0, got: " + multiplier);
        }
        this.multiplier = multiplier;
    }

    @Override
    public Duration computeDelay(int attemptNumber, Duration initialInterval) {
        double factor = Math.pow(multiplier, attemptNumber - 1);
        long millis = (long) (initialInterval.toMillis() * factor);
        return Duration.ofMillis(millis);
    }

    /** Returns the configured multiplier. */
    public double getMultiplier() {
        return multiplier;
    }

    @Override
    public String toString() {
        return "ExponentialBackoff{multiplier=" + multiplier + '}';
    }
}
