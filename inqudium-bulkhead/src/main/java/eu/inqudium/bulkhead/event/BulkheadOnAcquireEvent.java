package eu.inqudium.bulkhead.event;

import java.time.Instant;

/**
 * Emitted when a bulkhead permit is acquired.
 *
 * @since 0.1.0
 */
public class BulkheadOnAcquireEvent extends BulkheadEvent {
    private final int concurrentCalls;

    public BulkheadOnAcquireEvent(String callId, String elementName, int concurrentCalls, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.concurrentCalls = concurrentCalls;
    }

    /** Returns the number of concurrent calls after acquisition. */
    public int getConcurrentCalls() { return concurrentCalls; }
}
