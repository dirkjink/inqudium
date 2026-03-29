package eu.inqudium.core.ratelimiter;

import eu.inqudium.core.exception.InqException;

import eu.inqudium.core.InqElementType;

import java.time.Duration;
import java.util.Locale;

/**
 * Thrown when a rate limiter denies a request because no permits are available
 * and the wait timeout (if any) has expired.
 *
 * <p>No call was made to the downstream service — the rate limiter rejected
 * the request to protect throughput limits (ADR-009, ADR-019).
 *
 * @since 0.1.0
 */
public class InqRequestNotPermittedException extends InqException {

    /** Request denied — no permits available. */
    public static final String CODE = InqElementType.RATE_LIMITER.errorCode(1);

    private final Duration waitEstimate;

    /**
     * Creates a new exception indicating that the rate limiter denied the request.
     *
     * @param elementName  the rate limiter instance name
     * @param waitEstimate estimated duration until the next permit becomes available
     */
    public InqRequestNotPermittedException(String callId, String elementName, Duration waitEstimate) {
        super(callId, CODE, elementName, InqElementType.RATE_LIMITER,
                String.format(Locale.ROOT, "RateLimiter '%s' denied request (next permit in ~%dms)", elementName, waitEstimate.toMillis()));
        this.waitEstimate = waitEstimate;
    }

    /**
     * Returns the estimated wait time until the next permit.
     *
     * @return the estimated wait duration
     */
    public Duration getWaitEstimate() {
        return waitEstimate;
    }
}
