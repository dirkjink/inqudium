package eu.inqudium.core.element.bulkhead.event;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Base class for bulkhead events.
 *
 * @since 0.1.0
 */
public abstract class BulkheadEvent extends InqEvent {
    protected BulkheadEvent(long chainId, long callId, String elementName, Instant timestamp) {
        super(chainId, callId, elementName, InqElementType.BULKHEAD, timestamp);
    }
}
