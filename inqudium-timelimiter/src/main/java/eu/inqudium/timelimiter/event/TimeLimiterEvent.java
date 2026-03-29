package eu.inqudium.timelimiter.event;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Base class for time limiter events.
 *
 * @since 0.1.0
 */
public abstract class TimeLimiterEvent extends InqEvent {
    protected TimeLimiterEvent(String callId, String elementName, Instant timestamp) {
        super(callId, elementName, InqElementType.TIME_LIMITER, timestamp);
    }
}
