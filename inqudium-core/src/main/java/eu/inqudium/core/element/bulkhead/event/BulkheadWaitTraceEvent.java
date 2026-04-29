package eu.inqudium.core.element.bulkhead.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Trace event published when a blocking thread has finished waiting for a bulkhead permit.
 * It records the exact time spent in the wait queue, regardless of whether the permit
 * was ultimately acquired or the wait timed out.
 *
 * @since 0.2.0
 */
public final class BulkheadWaitTraceEvent extends BulkheadEvent {

    private final long waitDurationNanos;
    private final boolean acquired;

    /**
     * Creates a new trace event for a bulkhead wait operation.
     *
     * @param callId            the unique identifier of the call
     * @param elementName       the name of the bulkhead
     * @param waitDurationNanos the exact duration the thread spent waiting in nanoseconds
     * @param acquired          true if the permit was acquired, false if it timed out or was rejected
     * @param timestamp         the exact time the wait operation concluded
     */
    public BulkheadWaitTraceEvent(long chainId, long callId, String elementName, long waitDurationNanos, boolean acquired, Instant timestamp) {
        super(chainId, callId, elementName, timestamp);
        this.waitDurationNanos = waitDurationNanos;
        this.acquired = acquired;
    }

    public long getWaitDurationNanos() {
        return waitDurationNanos;
    }

    public boolean isAcquired() {
        return acquired;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BulkheadWaitTraceEvent that = (BulkheadWaitTraceEvent) o;
        return waitDurationNanos == that.waitDurationNanos &&
                acquired == that.acquired &&
                Objects.equals(getCallId(), that.getCallId()) &&
                Objects.equals(getElementName(), that.getElementName()) &&
                Objects.equals(getTimestamp(), that.getTimestamp());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                getCallId(),
                getElementName(),
                getTimestamp(),
                waitDurationNanos,
                acquired);
    }

    @Override
    public String toString() {
        return "BulkheadWaitTraceEvent{" +
                "callId='" + getCallId() + '\'' +
                ", elementName='" + getElementName() + '\'' +
                ", timestamp=" + getTimestamp() +
                ", waitDurationNanos=" + waitDurationNanos +
                ", acquired=" + acquired +
                '}';
    }
}
