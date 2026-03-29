package eu.inqudium.core;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Context-carrying wrapper for a call through the resilience pipeline.
 *
 * <p>Wraps a {@link Callable} rather than a {@code Supplier} so that checked
 * exceptions flow naturally through the decoration chain without intermediate
 * wrapping. The conversion to unchecked exceptions happens exactly once — at the
 * boundary where the pipeline returns a {@code Supplier} to the caller.
 *
 * <h2>Pipeline flow</h2>
 * <pre>
 * Callable (checked exceptions ok)
 *   → InqCall(callId, callable)
 *     → decorator.decorate(call) → call.withCallable(newCallable)
 *       → decorator.decorate(call) → call.withCallable(newCallable)
 *         → call.execute()  — throws Exception (natural for Callable)
 *   → Supplier boundary: checked exceptions wrapped in InqRuntimeException
 * </pre>
 *
 * <h2>Why Callable, not Supplier</h2>
 * <p>A {@code Supplier.get()} cannot declare checked exceptions. When the
 * downstream call throws a checked exception (e.g. {@code IOException}), each
 * element would need to wrap it individually. With {@code Callable.call()},
 * checked exceptions propagate naturally until the single wrapping point at
 * the {@code Supplier} boundary.
 *
 * @param callId   the unique call identifier shared across all elements (ADR-003)
 * @param callable the operation to execute
 * @param <T>      the result type
 * @since 0.1.0
 */
public record InqCall<T>(String callId, Callable<T> callable) {

    public InqCall {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(callable, "callable must not be null");
    }

    /**
     * Creates a new call with the given callId and callable.
     *
     * @param callId   the call identifier
     * @param callable the operation
     * @param <T>      the result type
     * @return a new InqCall
     */
    public static <T> InqCall<T> of(String callId, Callable<T> callable) {
        return new InqCall<>(callId, callable);
    }

    /**
     * Creates a new call with the same callId but a different callable.
     *
     * <p>Used by decorators to wrap the callable while preserving the callId:
     * <pre>{@code
     * return call.withCallable(() -> {
     *     acquirePermit(call.callId());
     *     return call.callable().call(); // checked exceptions flow naturally
     * });
     * }</pre>
     *
     * @param newCallable the decorated callable
     * @return a new InqCall with the same callId
     */
    public InqCall<T> withCallable(Callable<T> newCallable) {
        return new InqCall<>(this.callId, newCallable);
    }

    /**
     * Executes the callable and returns the result.
     *
     * <p>Throws the callable's checked exception directly — no wrapping.
     * The caller is responsible for handling or wrapping checked exceptions
     * at the {@code Supplier} boundary.
     *
     * @return the result of the call
     * @throws Exception if the callable throws
     */
    public T execute() throws Exception {
        return callable.call();
    }
}
