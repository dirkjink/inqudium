package eu.inqudium.core.element.circuitbreaker;

/**
 * Represents a state transition event emitted by the circuit breaker.
 *
 * @param name           the circuit breaker name
 * @param fromState      the state before the transition
 * @param toState        the state after the transition
 * @param timestampNanos nanosecond timestamp (from {@link eu.inqudium.core.time.InqNanoTimeSource}) when the transition occurred
 * @param reason         the human-readable explanation of why the transition happened
 */
public record StateTransition(
        String name,
        CircuitState fromState,
        CircuitState toState,
        long timestampNanos,
        String reason
) {

    @Override
    public String toString() {
        return "CircuitBreaker '%s': %s -> %s at nanos=%d. Reason: %s"
                .formatted(name, fromState, toState, timestampNanos, reason);
    }
}
