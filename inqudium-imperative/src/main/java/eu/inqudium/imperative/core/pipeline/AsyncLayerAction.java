package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.LayerAction;

import java.util.concurrent.CompletionStage;

/**
 * Package-private singleton for the async pass-through.
 */
enum AsyncPassThrough implements AsyncLayerAction<Object, Object> {
    INSTANCE;

    @Override
    public CompletionStage<Object> executeAsync(long chainId, long callId, Object argument,
                                                InternalAsyncExecutor<Object, Object> next) {
        return next.executeAsync(chainId, callId, argument);
    }
}

/**
 * Functional interface defining the behavior of a single layer in an async wrapper chain.
 *
 * <p>The async counterpart to {@link LayerAction}. Has the same <strong>around-semantics</strong>,
 * but the execution is split into two phases:</p>
 *
 * <ul>
 *   <li><strong>Start phase</strong> (synchronous): code before {@code next.execute()} runs on
 *       the calling thread. Use this to acquire permits, start timers, or set context.</li>
 *   <li><strong>End phase</strong> (asynchronous): code attached to the returned
 *       {@link CompletionStage} via {@code whenComplete()}, {@code thenApply()}, etc.
 *       runs when the async operation completes. Use this to release permits, stop timers,
 *       or record metrics.</li>
 * </ul>
 *
 * <h3>Bulkhead example (acquire sync, release async)</h3>
 * <pre>{@code
 * (chainId, callId, arg, next) -> {
 *     acquire();                                          // start phase — sync
 *     CompletionStage<T> stage;
 *     try {
 *         stage = next.executeAsync(chainId, callId, arg);     // delegate starts async op
 *     } catch (Throwable t) {
 *         release();                                      // cleanup on sync failure
 *         throw t;
 *     }
 *     stage.whenComplete((r, e) -> release());     // end phase — async
 *     return stage
 * }
 * }</pre>
 *
 * <h3>Timing example</h3>
 * <pre>{@code
 * (chainId, callId, arg, next) -> {
 *     long start = System.nanoTime();
 *     CompletionStage<R> stage = next.executeAsync(chainId, callId, arg)
 *     stage.whenComplete((r, e) -> metrics.record(System.nanoTime() - start));
 *     return stage
 *
 * }
 * }</pre>
 *
 * @param <A> the argument type flowing through the chain
 * @param <R> the result type carried by the CompletionStage
 */
@FunctionalInterface
public interface AsyncLayerAction<A, R> {

    /**
     * Returns a pass-through action that simply forwards to the next step.
     */
    @SuppressWarnings("unchecked")
    static <A, R> AsyncLayerAction<A, R> passThrough() {
        return (AsyncLayerAction<A, R>) AsyncPassThrough.INSTANCE;
    }

    /**
     * Executes this layer's async logic.
     *
     * @param chainId  identifies the wrapper chain
     * @param callId   identifies this particular invocation
     * @param argument the argument flowing through the chain
     * @param next     the next async step — call {@code next.execute(...)} to proceed
     * @return the <strong>same</strong> {@link CompletionStage} instance that the downstream
     * chain produced — guaranteed. Pipeline identity is preserved: callers may rely
     * on {@code returnedStage == originalFuture}. The permit-release callback is
     * attached via {@code whenComplete()} as a side-effect only.
     */
    CompletionStage<R> executeAsync(long chainId, long callId, A argument,
                                    InternalAsyncExecutor<A, R> next);
}
