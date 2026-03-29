package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElement;

/**
 * Decoration contract for resilience elements in a pipeline.
 *
 * <p>Element interfaces (CircuitBreaker, Retry, etc.) extend this interface.
 * The pipeline composes decorators by nesting: the outermost decorator wraps
 * the next, which wraps the next, down to the original supplier (ADR-002).
 *
 * <p>The {@link InqCall} carries the callId through the chain — no thread-local,
 * no hidden state. Every decorator reads {@code call.callId()} for event
 * correlation and wraps the supplier via {@code call.withSupplier(...)}.
 *
 * @since 0.1.0
 */
public interface InqDecorator extends InqElement {

    /**
     * Wraps a call with this element's resilience logic, preserving the callId.
     *
     * @param call the call to decorate (carries the shared callId)
     * @param <T>  the result type
     * @return a decorated call with the same callId
     */
    <T> InqCall<T> decorate(InqCall<T> call);
}
