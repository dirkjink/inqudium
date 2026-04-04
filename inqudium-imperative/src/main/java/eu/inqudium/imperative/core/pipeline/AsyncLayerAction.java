package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionStage;

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
 *     return stage.whenComplete((r, e) -> release());     // end phase — async
 * }
 * }</pre>
 *
 * <h3>Timing example</h3>
 * <pre>{@code
 * (chainId, callId, arg, next) -> {
 *     long start = System.nanoTime();
 *     return next.executeAsync(chainId, callId, arg)
 *         .whenComplete((r, e) -> metrics.record(System.nanoTime() - start));
 * }
 * }</pre>
 *
 * @param <A> the argument type flowing through the chain
 * @param <R> the result type carried by the CompletionStage
 */
@FunctionalInterface
public interface AsyncLayerAction<A, R> {

  /**
   * Executes this layer's async logic.
   *
   * @param chainId  identifies the wrapper chain
   * @param callId   identifies this particular invocation
   * @param argument the argument flowing through the chain
   * @param next     the next async step — call {@code next.execute(...)} to proceed
   * @return a CompletionStage, optionally enriched with completion handlers
   */
  CompletionStage<R> executeAsync(long chainId, long callId, A argument,
                              InternalAsyncExecutor<A, R> next);

  /**
   * Returns a pass-through action that simply forwards to the next step.
   */
  @SuppressWarnings("unchecked")
  static <A, R> AsyncLayerAction<A, R> passThrough() {
    return (AsyncLayerAction<A, R>) AsyncPassThrough.INSTANCE;
  }
}

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
