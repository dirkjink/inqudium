package eu.inqudium.circuitbreaker.event;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Base class for circuit breaker events.
 *
 * @since 0.1.0
 */
public abstract class CircuitBreakerEvent extends InqEvent {

    protected CircuitBreakerEvent(String callId, String elementName, Instant timestamp) {
        super(callId, elementName, InqElementType.CIRCUIT_BREAKER, timestamp);
    }
}
