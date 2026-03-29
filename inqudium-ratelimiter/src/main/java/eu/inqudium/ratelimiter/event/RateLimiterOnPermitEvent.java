package eu.inqudium.ratelimiter.event;

import java.time.Instant;

/**
 * Emitted when a permit is successfully acquired.
 *
 * @since 0.1.0
 */
public class RateLimiterOnPermitEvent extends RateLimiterEvent {
    private final int remainingTokens;

    public RateLimiterOnPermitEvent(String callId, String elementName, int remainingTokens, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.remainingTokens = remainingTokens;
    }

    public int getRemainingTokens() { return remainingTokens; }
}
