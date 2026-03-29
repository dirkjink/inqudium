package eu.inqudium.retry.event;

import java.time.Instant;

/**
 * Emitted when a call succeeds (on the first attempt or after retries).
 *
 * @since 0.1.0
 */
public class RetryOnSuccessEvent extends RetryEvent {

    private final int attemptNumber;

    public RetryOnSuccessEvent(String callId, String elementName, int attemptNumber, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.attemptNumber = attemptNumber;
    }

    /** Returns the attempt number on which the call succeeded (1 = first attempt). */
    public int getAttemptNumber() { return attemptNumber; }
}
