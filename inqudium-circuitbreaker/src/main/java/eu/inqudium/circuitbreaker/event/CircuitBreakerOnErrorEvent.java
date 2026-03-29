package eu.inqudium.circuitbreaker.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Emitted when a call through the circuit breaker fails with an exception.
 *
 * @since 0.1.0
 */
public class CircuitBreakerOnErrorEvent extends CircuitBreakerEvent {

    private final Duration duration;
    private final Throwable throwable;

    public CircuitBreakerOnErrorEvent(String callId, String elementName,
                                      Duration duration, Throwable throwable, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.duration = duration;
        this.throwable = throwable;
    }

    /** Returns the call duration. */
    public Duration getDuration() {
        return duration;
    }

    /** Returns the exception that caused the failure. */
    public Throwable getThrowable() {
        return throwable;
    }
}
