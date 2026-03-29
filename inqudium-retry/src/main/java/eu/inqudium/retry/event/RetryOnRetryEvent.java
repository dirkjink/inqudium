package eu.inqudium.retry.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Emitted when a retry attempt is about to be made.
 *
 * @since 0.1.0
 */
public class RetryOnRetryEvent extends RetryEvent {

    private final int attemptNumber;
    private final Duration waitDuration;
    private final Throwable lastException;

    public RetryOnRetryEvent(String callId, String elementName, int attemptNumber,
                              Duration waitDuration, Throwable lastException, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.attemptNumber = attemptNumber;
        this.waitDuration = waitDuration;
        this.lastException = lastException;
    }

    public int getAttemptNumber() { return attemptNumber; }
    public Duration getWaitDuration() { return waitDuration; }
    public Throwable getLastException() { return lastException; }
}
