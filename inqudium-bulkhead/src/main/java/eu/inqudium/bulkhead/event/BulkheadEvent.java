package eu.inqudium.bulkhead.event;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Base class for bulkhead events.
 *
 * @since 0.1.0
 */
public abstract class BulkheadEvent extends InqEvent {
    protected BulkheadEvent(String callId, String elementName, Instant timestamp) {
        super(callId, elementName, InqElementType.BULKHEAD, timestamp);
    }
}
