package eu.inqudium.circuitbreaker.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Emitted when a call through the circuit breaker completes successfully.
 *
 * @since 0.1.0
 */
public class CircuitBreakerOnSuccessEvent extends CircuitBreakerEvent {

    private final Duration duration;

    public CircuitBreakerOnSuccessEvent(String callId, String elementName,
                                        Duration duration, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.duration = duration;
    }

    /** Returns the call duration. */
    public Duration getDuration() {
        return duration;
    }
}
