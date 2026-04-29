package eu.inqudium.core.element.bulkhead.event;

import java.time.Instant;

/**
 * Emitted when a bulkhead permit is released.
 *
 * @since 0.1.0
 */
public class BulkheadOnReleaseEvent extends BulkheadEvent {
    private final int concurrentCalls;

    public BulkheadOnReleaseEvent(long chainId, long callId, String elementName, int concurrentCalls, Instant timestamp) {
        super(chainId, callId, elementName, timestamp);
        this.concurrentCalls = concurrentCalls;
    }

    public int getConcurrentCalls() {
        return concurrentCalls;
    }
}
