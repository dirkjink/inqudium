package eu.inqudium.core;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Context-carrying wrapper for a call through the resilience pipeline.
 *
 * <p>Instead of using thread-local state to propagate the call identity,
 * {@code InqCall} carries the context as part of the data flow. Every element
 * in the pipeline reads the {@code callId} from this object — no hidden state,
 * no thread-local, works identically across imperative, reactive, and coroutine
 * paradigms.
 *
 * <h2>Pipeline usage</h2>
 * <p>The pipeline creates an {@code InqCall} with a generated callId and passes
 * it through the decoration chain. Each element reads {@code call.callId()} for
 * event correlation:
 * <pre>{@code
 * // Pipeline generates:
 * var call = InqCall.of(callIdGenerator.generate(), () -> service.call());
 *
 * // Each element decorates:
 * var decorated = decorator.decorate(call);
 * }</pre>
 *
 * <h2>Standalone usage</h2>
 * <p>When an element is used outside a pipeline, the public API
 * ({@code cb.decorateSupplier(supplier)}) creates an {@code InqCall}
 * internally with a fresh callId.
 *
 * @param callId   the unique call identifier shared across all elements (ADR-003)
 * @param supplier the operation to execute
 * @param <T>      the result type
 * @since 0.1.0
 */
public record InqCall<T>(String callId, Supplier<T> supplier) {

    public InqCall {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");
    }

    /**
     * Creates a new call with the given callId and supplier.
     *
     * @param callId   the call identifier
     * @param supplier the operation
     * @param <T>      the result type
     * @return a new InqCall
     */
    public static <T> InqCall<T> of(String callId, Supplier<T> supplier) {
        return new InqCall<>(callId, supplier);
    }

    /**
     * Creates a new call with the same callId but a different supplier.
     *
     * <p>Used by decorators to wrap the supplier while preserving the callId:
     * <pre>{@code
     * return call.withSupplier(() -> {
     *     acquirePermit(call.callId());
     *     return call.supplier().get();
     * });
     * }</pre>
     *
     * @param newSupplier the decorated supplier
     * @return a new InqCall with the same callId
     */
    public InqCall<T> withSupplier(Supplier<T> newSupplier) {
        return new InqCall<>(this.callId, newSupplier);
    }

    /**
     * Executes the supplier and returns the result.
     *
     * @return the result of the call
     */
    public T execute() {
        return supplier.get();
    }
}
