package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.exception.InqException;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.circuitbreaker.CircuitBreakerState;

import java.util.Locale;

/**
 * Thrown when a circuit breaker rejects a call because it is in
 * {@link CircuitBreakerState#OPEN} or {@link CircuitBreakerState#HALF_OPEN}
 * state and no probe permits are available.
 *
 * <p>No call was made to the downstream service — the circuit breaker
 * short-circuited the request (ADR-009).
 *
 * @since 0.1.0
 */
public class InqCallNotPermittedException extends InqException {

    /** Call rejected — circuit breaker is OPEN. */
    public static final String CODE = InqElementType.CIRCUIT_BREAKER.errorCode(1);

    private final CircuitBreakerState state;
    private final float failureRate;

    /**
     * Creates a new exception indicating that the circuit breaker rejected the call.
     *
     * @param callId      the unique call identifier
     * @param elementName the circuit breaker instance name
     * @param state       the current state of the circuit breaker
     * @param failureRate the current failure rate (0.0 to 100.0)
     */
    public InqCallNotPermittedException(String callId, String elementName, CircuitBreakerState state, float failureRate) {
        super(callId, CODE, elementName, InqElementType.CIRCUIT_BREAKER,
                String.format(Locale.ROOT, "CircuitBreaker '%s' is %s (failure rate: %.1f%%)", elementName, state, failureRate));
        this.state = state;
        this.failureRate = failureRate;
    }

    /**
     * Returns the circuit breaker state at the time of rejection.
     *
     * @return the current state (typically {@link CircuitBreakerState#OPEN})
     */
    public CircuitBreakerState getState() {
        return state;
    }

    /**
     * Returns the failure rate at the time of rejection.
     *
     * @return the failure rate as a percentage (0.0 to 100.0)
     */
    public float getFailureRate() {
        return failureRate;
    }
}
