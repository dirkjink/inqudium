package eu.inqudium.ratelimiter.event;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Base class for rate limiter events.
 *
 * @since 0.1.0
 */
public abstract class RateLimiterEvent extends InqEvent {
    protected RateLimiterEvent(String callId, String elementName, Instant timestamp) {
        super(callId, elementName, InqElementType.RATE_LIMITER, timestamp);
    }
}
