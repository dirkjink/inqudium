package eu.inqudium.bulkhead.event;

import java.time.Instant;

/**
 * Emitted when a bulkhead permit request is denied.
 *
 * @since 0.1.0
 */
public class BulkheadOnRejectEvent extends BulkheadEvent {
    private final int concurrentCalls;

    public BulkheadOnRejectEvent(String callId, String elementName, int concurrentCalls, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.concurrentCalls = concurrentCalls;
    }

    public int getConcurrentCalls() { return concurrentCalls; }
}
