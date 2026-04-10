package eu.inqudium.core.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Direct executor for functional interfaces through a {@link LayerAction}.
 *
 * <p>While the {@code decorateXxx} factory methods on {@link InqDecorator} create wrapper
 * objects for deferred, reusable execution, the {@code executeXxx} methods on this interface
 * invoke the {@link LayerAction} <strong>immediately</strong> without creating any
 * wrapper infrastructure. The only allocation per call is the single
 * {@link InternalExecutor} lambda that adapts the functional interface — everything
 * else (chain ID, call ID) uses primitives from {@link StandaloneIdGenerator}.</p>
 *
 * <h3>Comparison: decorate vs. execute</h3>
 * <pre>{@code
 * // decorate: creates a SupplierWrapper (deferred, reusable)
 * Supplier<String> reusable = bulkhead.decorateSupplier(() -> callApi());
 * reusable.get();  // can call multiple times
 *
 * // execute: invokes the LayerAction immediately (one-shot, zero wrapper overhead)
 * String result = bulkhead.executeSupplier(() -> callApi());
 * }</pre>
 *
 * <h3>When to use executeXxx vs. decorateXxx</h3>
 * <ul>
 *   <li>Use {@code executeXxx} for one-shot operations where you don't need a
 *       reusable wrapper object — saves the allocation of a wrapper + core lambda.</li>
 *   <li>Use {@code decorateXxx} when the wrapped operation will be called repeatedly
 *       or when you need to compose multiple decorator layers.</li>
 * </ul>
 *
 * <h3>Exception Handling</h3>
 * <p>The same two-phase strategy as the wrapper classes is used for checked exceptions:
 * they are wrapped in {@link CompletionException} for transport through the
 * {@link LayerAction} and unwrapped before returning to the caller.</p>
 *
 * @param <A> the argument type flowing through the layer
 * @param <R> the return type flowing back through the layer
 */
public interface InqExecutor<A, R> extends LayerAction<A, R> {

    /**
     * Executes a {@link Runnable} directly through this layer's around-advice.
     *
     * <p>Generates a standalone chain ID and call ID for this one-shot execution.
     * The argument is always {@code null} (Runnable has no input).</p>
     *
     * <p>Intended for executors with type parameters {@code <Void, Void>}.
     * The unchecked cast is safe because both argument and return are always {@code null}.</p>
     *
     * @param runnable the operation to execute immediately
     */
    @SuppressWarnings("unchecked")
    default void executeRunnable(Runnable runnable) {
        ((LayerAction<Void, Void>) this).execute(
                StandaloneIdGenerator.nextChainId(),
                StandaloneIdGenerator.nextCallId(),
                null,
                // Terminal lambda: invokes the runnable and returns null (Void return)
                (chainId, callId, arg) -> {
                    runnable.run();
                    return null;
                }
        );
    }

    /**
     * Executes a {@link Supplier} directly through this layer's around-advice
     * and returns its result.
     *
     * <p>Intended for executors with type parameters {@code <Void, T>}.</p>
     *
     * @param supplier the operation to execute immediately
     * @param <T>      the return type of the supplier
     * @return the supplier's result, after passing through the around-advice
     */
    @SuppressWarnings("unchecked")
    default <T> T executeSupplier(Supplier<T> supplier) {
        return ((LayerAction<Void, T>) this).execute(
                StandaloneIdGenerator.nextChainId(),
                StandaloneIdGenerator.nextCallId(),
                null,
                // Terminal lambda: invokes the supplier and returns its value
                (chainId, callId, arg) -> supplier.get()
        );
    }

    /**
     * Executes a {@link Callable} directly through this layer's around-advice
     * and returns its result. Checked exceptions are preserved.
     *
     * <p>Uses the same two-phase checked-exception transport as
     * {@link CallableWrapper}: the terminal lambda wraps checked exceptions in
     * {@link CompletionException}, and this method unwraps them before returning.</p>
     *
     * <p>Intended for executors with type parameters {@code <Void, V>}.</p>
     *
     * @param callable the operation to execute immediately
     * @param <V>      the return type of the callable
     * @return the callable's result, after passing through the around-advice
     * @throws Exception the original checked exception from the callable, if any
     */
    @SuppressWarnings("unchecked")
    default <V> V executeCallable(Callable<V> callable) throws Exception {
        try {
            return ((LayerAction<Void, V>) this).execute(
                    StandaloneIdGenerator.nextChainId(),
                    StandaloneIdGenerator.nextCallId(),
                    null,
                    (chainId, callId, arg) -> {
                        try {
                            return callable.call();
                        } catch (RuntimeException | Error e) {
                            // Unchecked — propagate directly
                            throw e;
                        } catch (Exception e) {
                            // Checked — wrap for transport through the LayerAction
                            throw new CompletionException(e);
                        }
                    }
            );
        } catch (RuntimeException e) {
            // Unwrap the checked exception if it was wrapped during transport
            if (e instanceof CompletionException) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new CompletionException(cause);
            }
            throw e;
        }
    }

    /**
     * Executes a {@link Function} directly through this layer's around-advice
     * with the given input and returns its result.
     *
     * <p>This method is fully type-safe — the executor's type parameters {@code <A, R>}
     * naturally match the function's input and output types.</p>
     *
     * @param function the operation to execute immediately
     * @param input    the function argument
     * @return the function's result, after passing through the around-advice
     */
    default R executeFunction(Function<A, R> function, A input) {
        return execute(
                StandaloneIdGenerator.nextChainId(),
                StandaloneIdGenerator.nextCallId(),
                input,
                // Terminal lambda: invokes the function with the chain argument
                (chainId, callId, arg) -> function.apply(arg)
        );
    }

    /**
     * Executes a {@link JoinPointExecutor} directly through this layer's around-advice
     * and returns its result. All throwable types are preserved.
     *
     * <p>Uses the same checked-exception transport as {@link JoinPointWrapper}:
     * non-Exception throwables are wrapped in {@link CompletionException} and
     * unwrapped before returning.</p>
     *
     * <p>Intended for executors with type parameters {@code <Void, T>}.</p>
     *
     * @param execution the proxy operation to execute immediately
     * @param <T>       the return type of the proxy execution
     * @return the execution's result, after passing through the around-advice
     * @throws Throwable the original exception from the proxy execution, if any
     */
    @SuppressWarnings("unchecked")
    default <T> T executeJoinPoint(JoinPointExecutor<T> execution) throws Throwable {
        try {
            return ((LayerAction<Void, T>) this).execute(
                    StandaloneIdGenerator.nextChainId(),
                    StandaloneIdGenerator.nextCallId(),
                    null,
                    (chainId, callId, arg) -> {
                        try {
                            return execution.proceed();
                        } catch (RuntimeException | Error e) {
                            // Unchecked — propagate directly
                            throw e;
                        } catch (Throwable t) {
                            // Checked throwable — wrap for transport
                            throw new CompletionException(t);
                        }
                    }
            );
        } catch (RuntimeException e) {
            // Unwrap the original throwable if it was wrapped during transport
            if (e instanceof CompletionException) {
                throw e.getCause();
            }
            throw e;
        }
    }
}
