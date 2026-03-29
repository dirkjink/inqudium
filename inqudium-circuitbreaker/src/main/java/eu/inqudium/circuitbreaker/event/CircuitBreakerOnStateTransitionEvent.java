package eu.inqudium.circuitbreaker.event;

import eu.inqudium.core.circuitbreaker.CircuitBreakerState;

import java.time.Instant;

/**
 * Emitted when the circuit breaker transitions between states.
 *
 * @since 0.1.0
 */
public class CircuitBreakerOnStateTransitionEvent extends CircuitBreakerEvent {

    private final CircuitBreakerState fromState;
    private final CircuitBreakerState toState;

    public CircuitBreakerOnStateTransitionEvent(String callId, String elementName,
                                                 CircuitBreakerState fromState,
                                                 CircuitBreakerState toState,
                                                 Instant timestamp) {
        super(callId, elementName, timestamp);
        this.fromState = fromState;
        this.toState = toState;
    }

    /** Returns the state before the transition. */
    public CircuitBreakerState getFromState() {
        return fromState;
    }

    /** Returns the state after the transition. */
    public CircuitBreakerState getToState() {
        return toState;
    }
}
