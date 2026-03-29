package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElement;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Decoration contract for resilience elements.
 *
 * <p>Provides two decoration modes:
 * <ul>
 *   <li><strong>Standalone:</strong> {@link #decorateCallable(Callable)},
 *       {@link #decorateSupplier(Supplier)}, and {@link #decorateRunnable(Runnable)}
 *       for direct use without a pipeline. Each call generates its own {@code callId}.</li>
 *   <li><strong>Pipeline:</strong> {@link #decorate(InqCall)} for use inside an
 *       {@link InqPipeline}. The {@link InqCall} carries the shared {@code callId}
 *       through the entire decoration chain (ADR-022).</li>
 * </ul>
 *
 * <h2>Method hierarchy</h2>
 * <p>{@code decorateCallable} is the core method that implementations must provide.
 * All other decoration methods delegate to it:
 * <pre>
 * decorateCallable(Callable)   ← abstract, implemented by each element
 *   ↑ decorateSupplier(Supplier)  — wraps supplier as callable, delegates
 *   ↑ decorateRunnable(Runnable)  — wraps runnable as callable, delegates
 * </pre>
 *
 * <p>Each decoration method has a corresponding execution method that decorates
 * and immediately invokes:
 * <pre>
 * decorateCallable  → executeCallable
 * decorateSupplier  → executeSupplier
 * decorateRunnable  → executeRunnable
 * </pre>
 *
 * @since 0.1.0
 */
public interface InqDecorator extends InqElement {

    // ── Pipeline mode ──

    /**
     * Wraps a call with this element's resilience logic, preserving the callId.
     *
     * <p>Used by {@link InqPipeline} to compose multiple elements into a single
     * decoration chain. The {@link InqCall} carries the shared callId — each
     * element reads {@code call.callId()} for event correlation.
     *
     * @param call the call to decorate (carries the shared callId)
     * @param <T>  the result type
     * @return a decorated call with the same callId
     */
    <T> InqCall<T> decorate(InqCall<T> call);

    // ── Standalone mode — core method ──

    /**
     * Decorates a callable with this element's resilience logic.
     *
     * <p>This is the core decoration method. All other standalone decoration
     * methods ({@link #decorateSupplier}, {@link #decorateRunnable}) delegate
     * to this method.
     *
     * <p>Implementations must handle checked exceptions from the callable by
     * wrapping them in {@link eu.inqudium.core.exception.InqRuntimeException}
     * with the element's name and type (ADR-009, Category 2).
     *
     * <p>Each invocation of the returned supplier generates a fresh callId
     * via the element's {@code InqCallIdGenerator}.
     *
     * @param callable the callable to decorate
     * @param <T>      the result type
     * @return a decorated supplier (checked exceptions wrapped in InqRuntimeException)
     */
    <T> Supplier<T> decorateCallable(Callable<T> callable);

    // ── Standalone mode — delegating methods ──

    /**
     * Decorates a supplier with this element's resilience logic.
     *
     * <p>Default implementation wraps the supplier as a callable and delegates
     * to {@link #decorateCallable(Callable)}.
     *
     * @param supplier the supplier to decorate
     * @param <T>      the result type
     * @return a decorated supplier
     */
    default <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
        return decorateCallable(supplier::get);
    }

    /**
     * Decorates a runnable with this element's resilience logic.
     *
     * <p>Default implementation wraps the runnable as a callable and delegates
     * to {@link #decorateCallable(Callable)}.
     *
     * @param runnable the runnable to decorate
     * @return a decorated runnable
     */
    default Runnable decorateRunnable(Runnable runnable) {
        Supplier<Void> decorated = decorateCallable(() -> {
            runnable.run();
            return null;
        });
        return decorated::get;
    }

    // ── Execute methods — decorate and immediately invoke ──

    /**
     * Decorates and immediately executes a callable.
     *
     * @param callable the callable to execute
     * @param <T>      the result type
     * @return the result
     */
    default <T> T executeCallable(Callable<T> callable) {
        return decorateCallable(callable).get();
    }

    /**
     * Decorates and immediately executes a supplier.
     *
     * @param supplier the supplier to execute
     * @param <T>      the result type
     * @return the result
     */
    default <T> T executeSupplier(Supplier<T> supplier) {
        return decorateSupplier(supplier).get();
    }

    /**
     * Decorates and immediately executes a runnable.
     *
     * @param runnable the runnable to execute
     */
    default void executeRunnable(Runnable runnable) {
        decorateRunnable(runnable).run();
    }
}
