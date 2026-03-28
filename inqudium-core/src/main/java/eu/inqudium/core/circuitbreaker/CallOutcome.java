package eu.inqudium.core.circuitbreaker;

import java.time.Instant;

/**
 * Outcome of a single call recorded in the sliding window.
 *
 * @param success       whether the call succeeded
 * @param durationNanos call duration in nanoseconds
 * @param timestamp     when the call completed (provided by {@link eu.inqudium.core.InqClock})
 * @since 0.1.0
 */
public record CallOutcome(boolean success, long durationNanos, Instant timestamp) {

    /** Creates a successful outcome. */
    public static CallOutcome success(long durationNanos, Instant timestamp) {
        return new CallOutcome(true, durationNanos, timestamp);
    }

    /** Creates a failed outcome. */
    public static CallOutcome failure(long durationNanos, Instant timestamp) {
        return new CallOutcome(false, durationNanos, timestamp);
    }
}
