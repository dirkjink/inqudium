package eu.inqudium.ratelimiter.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Emitted when a permit request is denied.
 *
 * @since 0.1.0
 */
public class RateLimiterOnRejectEvent extends RateLimiterEvent {
    private final Duration waitEstimate;

    public RateLimiterOnRejectEvent(String callId, String elementName, Duration waitEstimate, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.waitEstimate = waitEstimate;
    }

    public Duration getWaitEstimate() { return waitEstimate; }
}
