package eu.inqudium.core.ratelimiter;

import java.time.Duration;

/**
 * Exception thrown when a call is rejected because the rate limit is exceeded.
 */
public class RateLimiterException extends RuntimeException {

    private final String rateLimiterName;
    private final Duration waitDuration;
    private final int availablePermits;

    public RateLimiterException(String rateLimiterName, Duration waitDuration, int availablePermits) {
        super("RateLimiter '%s' — no permits available, estimated wait: %s ms"
                .formatted(rateLimiterName, waitDuration.toMillis()));
        this.rateLimiterName = rateLimiterName;
        this.waitDuration = waitDuration;
        this.availablePermits = availablePermits;
    }

    public String getRateLimiterName() {
        return rateLimiterName;
    }

    /**
     * Returns the estimated duration the caller would need to wait
     * for a permit to become available.
     */
    public Duration getWaitDuration() {
        return waitDuration;
    }

    /**
     * Returns the number of available permits at the time of rejection.
     */
    public int getAvailablePermits() {
        return availablePermits;
    }
}
