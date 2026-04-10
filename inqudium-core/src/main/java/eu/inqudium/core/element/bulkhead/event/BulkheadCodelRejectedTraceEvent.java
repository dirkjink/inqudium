package eu.inqudium.core.element.bulkhead.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Trace event published when a bulkhead utilizing CoDel (Controlled Delay) rejects
 * a permit request because the request spent too much time waiting in the queue.
 *
 * <p>It records the exact sojourn time (queue duration) and the configured
 * target delay to allow for precise telemetry and tuning of the CoDel parameters.
 *
 * @since 0.2.0
 */
public final class BulkheadCodelRejectedTraceEvent extends BulkheadEvent {

    private final long sojournTimeNanos;
    private final long targetDelayNanos;

    /**
     * Creates a new trace event for a CoDel-based permit rejection.
     *
     * @param callId           the unique identifier of the call
     * @param elementName      the name of the bulkhead
     * @param sojournTimeNanos the exact time the request spent in the queue in nanoseconds
     * @param targetDelayNanos the configured target delay that was exceeded
     * @param timestamp        the exact time the rejection occurred
     */
    public BulkheadCodelRejectedTraceEvent(long chainId, long callId, String elementName, long sojournTimeNanos, long targetDelayNanos, Instant timestamp) {
        super(chainId, callId, elementName, timestamp);
        this.sojournTimeNanos = sojournTimeNanos;
        this.targetDelayNanos = targetDelayNanos;
    }

    public long getSojournTimeNanos() {
        return sojournTimeNanos;
    }

    public long getTargetDelayNanos() {
        return targetDelayNanos;
    }

    /**
     * Calculates by how many nanoseconds the actual queue time exceeded the target delay.
     *
     * @return the exceedance duration in nanoseconds
     */
    public long getExceedanceNanos() {
        return Math.max(0, sojournTimeNanos - targetDelayNanos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BulkheadCodelRejectedTraceEvent that = (BulkheadCodelRejectedTraceEvent) o;
        return sojournTimeNanos == that.sojournTimeNanos &&
                targetDelayNanos == that.targetDelayNanos &&
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
                sojournTimeNanos,
                targetDelayNanos);
    }

    @Override
    public String toString() {
        return "BulkheadCodelRejectedTraceEvent{" +
                "callId='" + getCallId() + '\'' +
                ", elementName='" + getElementName() + '\'' +
                ", timestamp=" + getTimestamp() +
                ", sojournTimeNanos=" + sojournTimeNanos +
                ", targetDelayNanos=" + targetDelayNanos +
                '}';
    }
}