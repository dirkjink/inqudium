package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.InternalExecutor;

import java.util.concurrent.CompletionStage;

/**
 * Internal async contract for propagating a call through the async wrapper chain.
 *
 * <p>Package-private. The async counterpart to {@link InternalExecutor}. Each layer
 * delegates to the next via {@code execute}, which returns a {@link CompletionStage}
 * instead of a direct result.</p>
 *
 * @param <A> the argument type passed through the chain
 * @param <R> the result type carried by the CompletionStage
 */
public interface InternalAsyncExecutor<A, R> {

    /**
     * Executes this layer and propagates to the next.
     *
     * @param chainId  identifies the wrapper chain
     * @param callId   identifies this particular invocation
     * @param argument the argument flowing through the chain
     * @return a CompletionStage that completes with the result of the innermost delegate
     */
    CompletionStage<R> executeAsync(long chainId, long callId, A argument);
}
