package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqConfig;
import eu.inqudium.core.InqElement;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqRuntimeException;

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
 * <p>Implementations must provide only two methods:
 * <ul>
 *   <li>{@link #decorate(InqCall)} — pipeline-mode decoration</li>
 *   <li>{@link #getConfig()} — configuration access for callId generation and logging</li>
 * </ul>
 *
 * <p>All standalone methods are provided as defaults that delegate to
 * {@link #decorate(InqCall)}:
 * <pre>
 * decorate(InqCall)      ← abstract, implemented by each element
 *   ↑ decorateCallable   — generates callId, creates InqCall, delegates, wraps boundary
 *     ↑ decorateSupplier — wraps supplier as callable, delegates
 *     ↑ decorateRunnable — wraps runnable as callable, delegates
 * </pre>
 *
 * <p>Each decoration method has a corresponding execution method:
 * <pre>
 * decorateCallable  → executeCallable
 * decorateSupplier  → executeSupplier
 * decorateRunnable  → executeRunnable
 * </pre>
 *
 * @since 0.1.0
 */
public interface InqDecorator extends InqElement {

    // ── Configuration access ──

    /**
     * Returns the element's configuration.
     *
     * <p>Used by the default {@link #decorateCallable(Callable)} implementation
     * to access the {@code InqCallIdGenerator} and the SLF4J {@code Logger}.
     *
     * <p>Element interfaces override the return type covariantly
     * (e.g. {@code CircuitBreakerConfig getConfig()}).
     *
     * @return the element configuration
     */
    InqConfig getConfig();

    // ── Pipeline mode ──

    /**
     * Wraps a call with this element's resilience logic, preserving the callId.
     *
     * <p>Used by {@link InqPipeline} to compose multiple elements into a single
     * decoration chain. The {@link InqCall} carries the shared callId — each
     * element reads {@code call.callId()} for event correlation.
     *
     * <p>This is the only method that element implementations must provide.
     * All standalone decoration methods delegate to this method via the
     * default {@link #decorateCallable(Callable)} template.
     *
     * @param call the call to decorate (carries the shared callId)
     * @param <T>  the result type
     * @return a decorated call with the same callId
     */
    <T> InqCall<T> decorate(InqCall<T> call);

    // ── Standalone mode — template method ──

    /**
     * Decorates a callable with this element's resilience logic.
     *
     * <p>This is the Supplier boundary — the point where checked exceptions
     * from the {@link Callable} are converted to unchecked exceptions. The
     * default implementation:
     * <ol>
     *   <li>Generates a fresh callId via {@code getConfig().getCallIdGenerator()}</li>
     *   <li>Wraps the callable in an {@link InqCall}</li>
     *   <li>Delegates to {@link #decorate(InqCall)} for the element-specific logic</li>
     *   <li>Executes the decorated call</li>
     *   <li>At the boundary: logs and rethrows runtime exceptions, wraps checked
     *       exceptions in {@link InqRuntimeException}</li>
     * </ol>
     *
     * <p>{@link InqException} subclasses (circuit breaker open, rate limit denied, etc.)
     * are <em>not</em> error-logged because they represent expected element behavior,
     * not downstream failures.
     *
     * @param callable the callable to decorate
     * @param <T>      the result type
     * @return a decorated supplier (checked exceptions wrapped in InqRuntimeException)
     */
    default <T> Supplier<T> decorateCallable(Callable<T> callable) {
        return () -> {
            var callId = getConfig().getCallIdGenerator().generate();
            var call = InqCall.of(callId, callable);
            try {
                return decorate(call).execute();
            } catch (InqException ie) {
                // Expected element behavior (CB open, BH full, etc.) — rethrow without logging
                throw ie;
            } catch (RuntimeException re) {
                getConfig().getLogger().error("[{}] {} '{}': {}",
                        callId, getElementType(), getName(), re.toString());
                throw re;
            } catch (Exception e) {
                getConfig().getLogger().error("[{}] {} '{}': {}",
                        callId, getElementType(), getName(), e.toString());
                throw new InqRuntimeException(callId, getName(), getElementType(), e);
            }
        };
    }

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
