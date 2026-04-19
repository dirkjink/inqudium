package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.InqExecutor;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.PipelineIds;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Immediate async executor for functional interfaces through an {@link AsyncLayerAction}.
 *
 * <p>The async counterpart to {@link InqExecutor}. Invokes the around-advice immediately
 * and returns a {@link CompletionStage} — no wrapper objects are created. The only
 * allocation per call is a single {@link InternalAsyncExecutor} lambda.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqAsyncExecutor<Void, String> executor = ...;
 *
 * CompletionStage<Void>   stage = executor.executeAsyncRunnable(() -> sendAsync(msg));
 * CompletionStage<String> stage = executor.executeAsyncSupplier(() -> fetchAsync());
 * CompletionStage<String> stage = executor.executeAsyncCallable(() -> readAsync());
 * CompletionStage<R>      stage = executor.executeAsyncFunction(id -> loadAsync(id), "key");
 * CompletionStage<String> stage = executor.executeAsyncJoinPoint(() -> proceedAsync());
 * }</pre>
 *
 * @param <A> the argument type flowing through the layer
 * @param <R> the result type carried by the CompletionStage
 */
public interface InqAsyncExecutor<A, R> extends AsyncLayerAction<A, R> {

    /**
     * Executes an async runnable directly through this layer's around-advice.
     */
    @SuppressWarnings("unchecked")
    default CompletionStage<Void> executeAsyncRunnable(Runnable runnable) {
        return ((AsyncLayerAction<Void, Void>) this).executeAsync(
                PipelineIds.nextChainId(),
                PipelineIds.nextStandaloneCallId(),
                null,
                (chainId, callId, arg) -> {
                    runnable.run();
                    return null;
                }
        );
    }

    /**
     * Executes an async supplier directly through this layer's around-advice.
     */
    @SuppressWarnings("unchecked")
    default <T> CompletionStage<T> executeAsyncSupplier(Supplier<CompletionStage<T>> supplier) {
        return ((AsyncLayerAction<Void, T>) this).executeAsync(
                PipelineIds.nextChainId(),
                PipelineIds.nextStandaloneCallId(),
                null,
                (chainId, callId, arg) -> supplier.get()
        );
    }

    /**
     * Executes an async callable directly through this layer's around-advice.
     * Checked exceptions from starting the async operation are preserved.
     */
    @SuppressWarnings("unchecked")
    default <V> CompletionStage<V> executeAsyncCallable(
            Callable<CompletionStage<V>> callable) throws Exception {
        try {
            return ((AsyncLayerAction<Void, V>) this).executeAsync(
                    PipelineIds.nextChainId(),
                    PipelineIds.nextStandaloneCallId(),
                    null,
                    (chainId, callId, arg) -> {
                        try {
                            return callable.call();
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }
            );
        } catch (RuntimeException e) {
            if (e instanceof CompletionException) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new CompletionException(cause);
            }
            throw e;
        }
    }

    /**
     * Executes an async function directly through this layer's around-advice.
     * Fully type-safe — {@code A} and {@code R} match the function's types.
     */
    default CompletionStage<R> executeAsyncFunction(
            Function<A, CompletionStage<R>> function, A input) {
        return executeAsync(
                PipelineIds.nextChainId(),
                PipelineIds.nextStandaloneCallId(),
                input,
                (chainId, callId, arg) -> function.apply(arg)
        );
    }

    /**
     * Executes an async proxy execution directly through this layer's around-advice.
     * Checked throwables from starting the async operation are preserved.
     */
    @SuppressWarnings("unchecked")
    default <T> CompletionStage<T> executeAsyncJoinPoint(
            JoinPointExecutor<CompletionStage<T>> execution) throws Throwable {
        try {
            return ((AsyncLayerAction<Void, T>) this).executeAsync(
                    PipelineIds.nextChainId(),
                    PipelineIds.nextStandaloneCallId(),
                    null,
                    (chainId, callId, arg) -> {
                        try {
                            return execution.proceed();
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Throwable t) {
                            throw new CompletionException(t);
                        }
                    }
            );
        } catch (RuntimeException e) {
            if (e instanceof CompletionException) {
                throw e.getCause();
            }
            throw e;
        }
    }
}
