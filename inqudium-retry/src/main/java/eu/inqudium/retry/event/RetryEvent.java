package eu.inqudium.retry.event;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Base class for retry events.
 *
 * @since 0.1.0
 */
public abstract class RetryEvent extends InqEvent {

    protected RetryEvent(String callId, String elementName, Instant timestamp) {
        super(callId, elementName, InqElementType.RETRY, timestamp);
    }
}
