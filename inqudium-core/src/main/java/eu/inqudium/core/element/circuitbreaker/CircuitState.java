package eu.inqudium.core.element.circuitbreaker;

/**
 * Represents the three possible states of a circuit breaker.
 *
 * <p>State transitions follow this pattern:
 * <pre>
 *   CLOSED --[failure threshold reached]--> OPEN
 *   OPEN   --[wait duration expired]------> HALF_OPEN
 *   HALF_OPEN --[success threshold met]---> CLOSED
 *   HALF_OPEN --[any failure]-------------> OPEN
 * </pre>
 */
public enum CircuitState {

    /**
     * Circuit is closed — all calls are permitted.
     * Failures are tracked, and if the failure threshold is reached,
     * the circuit transitions to {@link #OPEN}.
     */
    CLOSED,

    /**
     * Circuit is open — all calls are rejected immediately.
     * After the configured wait duration expires, the circuit
     * transitions to {@link #HALF_OPEN}.
     */
    OPEN,

    /**
     * Circuit is half-open — a limited number of probe calls are permitted.
     * If enough succeed, the circuit transitions back to {@link #CLOSED}.
     * If any fails, it transitions back to {@link #OPEN}.
     */
    HALF_OPEN
}
