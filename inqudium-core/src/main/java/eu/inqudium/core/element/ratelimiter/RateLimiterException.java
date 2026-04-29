package eu.inqudium.core.element.ratelimiter;

import java.time.Duration;

/**
 * Exception thrown when a call is rejected because the rate limit is exceeded.
 */
public class RateLimiterException extends RuntimeException {

    private final String rateLimiterName;
    private final String instanceId;
    private final Duration waitDuration;
    private final int availablePermits;

    /**
     * Creates a new RateLimiterException.
     *
     * <p>Fix 8: Includes an {@code instanceId} to enable identity-based comparison
     * in fallback wrappers. Name-based comparison is fragile when multiple rate
     * limiters share the same human-readable name.
     *
     * @param rateLimiterName  human-readable name
     * @param instanceId       unique instance identifier (UUID-based)
     * @param waitDuration     estimated wait for a permit to become available
     * @param availablePermits permits available at time of rejection
     */
    public RateLimiterException(
            String rateLimiterName,
            String instanceId,
            Duration waitDuration,
            int availablePermits) {
        super("RateLimiter '%s' — no permits available, estimated wait: %s ms"
                .formatted(rateLimiterName, waitDuration.toMillis()));
        this.rateLimiterName = rateLimiterName;
        this.instanceId = instanceId;
        this.waitDuration = waitDuration;
        this.availablePermits = availablePermits;
    }

    public String getRateLimiterName() {
        return rateLimiterName;
    }

    /**
     * Fix 8: Returns the unique instance identifier of the rate limiter
     * that produced this exception. Use this instead of the name for
     * identity-based comparisons.
     */
    public String getInstanceId() {
        return instanceId;
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
