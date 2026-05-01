package eu.inqudium.imperative.lifecycle.spi;

import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.util.concurrent.CompletionStage;

/**
 * Async extension of {@link ImperativePhase}.
 *
 * <p>A phase that supports both synchronous and asynchronous dispatch implements this
 * interface. The lifecycle base class
 * ({@code ImperativeLifecyclePhasedComponent}) detects async-capable phases via
 * {@code instanceof AsyncImperativePhase} and routes
 * {@link eu.inqudium.imperative.core.pipeline.InqAsyncDecorator#executeAsync executeAsync}
 * calls accordingly.
 *
 * <p>The async phase shape mirrors {@link AsyncLayerAction} — the start phase runs
 * synchronously on the calling thread, the end phase runs asynchronously when the
 * downstream {@link CompletionStage} completes.
 *
 * @param <A> the call argument type flowing through the chain.
 * @param <R> the call return type flowing back through the chain.
 */
public interface AsyncImperativePhase<A, R> extends ImperativePhase<A, R> {

    /**
     * Execute this phase's async around-advice.
     *
     * @param chainId  identifies the wrapper chain.
     * @param callId   identifies this particular invocation.
     * @param argument the argument flowing through the chain.
     * @param next     the next async step in the chain.
     * @return a {@link CompletionStage} that carries the downstream result; per ADR-023, this
     *         is the decorated copy produced by the layer's {@code whenComplete(...)},
     *         except on the fast path where the downstream stage is already complete on entry.
     */
    CompletionStage<R> executeAsync(
            long chainId, long callId, A argument, InternalAsyncExecutor<A, R> next);
}
