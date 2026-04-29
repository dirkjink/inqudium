package eu.inqudium.aspect.pipeline;

import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;

/**
 * Defines a single asynchronous cross-cutting layer that can be contributed
 * to an aspect's async wrapper pipeline.
 *
 * <p>The async counterpart to {@link AspectLayerProvider}. Implementations
 * represent one concern (timing, bulkhead, circuit-breaker, etc.) and provide
 * an {@link AsyncLayerAction} with two-phase around-semantics:</p>
 * <ul>
 *   <li><strong>Start phase</strong> (synchronous): runs on the calling thread
 *       before the downstream {@link java.util.concurrent.CompletionStage} is created.</li>
 *   <li><strong>End phase</strong> (asynchronous): attached via {@code whenComplete()},
 *       {@code thenApply()}, etc. — runs when the async operation completes.</li>
 * </ul>
 *
 * <h3>Ordering</h3>
 * <p>Identical semantics to {@link AspectLayerProvider}: lower {@link #order()} values
 * produce outermost layers. A bulkhead at {@code order() = 10} wraps a timing layer
 * at {@code order() = 20}.</p>
 *
 * <h3>Example: async timing layer</h3>
 * <pre>{@code
 * public class AsyncTimingLayerProvider implements AsyncAspectLayerProvider<Object> {
 *     @Override public String layerName() { return "ASYNC_TIMING"; }
 *     @Override public int order() { return 20; }
 *
 *     @Override
 *     public AsyncLayerAction<Void, Object> asyncLayerAction() {
 *         return (chainId, callId, arg, next) -> {
 *             long start = System.nanoTime();
 *             CompletionStage<Object> stage = next.executeAsync(chainId, callId, arg);
 *             return stage.whenComplete((r, e) ->
 *                 metrics.record(System.nanoTime() - start));
 *         };
 *     }
 * }
 * }</pre>
 *
 * @param <R> the result type carried by the {@link java.util.concurrent.CompletionStage}
 */
public interface AsyncAspectLayerProvider<R> {

    /**
     * Returns a human-readable name for this layer, used in diagnostics
     * and {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()} output.
     *
     * @return a descriptive, non-null layer name
     */
    String layerName();

    /**
     * Returns the priority of this layer within the async pipeline.
     *
     * <p>Lower values produce outermost layers. Layers with equal order values
     * retain their registration order (stable sort).</p>
     *
     * @return an integer priority value
     */
    int order();

    /**
     * Returns the async around-advice logic for this layer.
     *
     * <p>The returned {@link AsyncLayerAction} controls the async invocation flow:
     * it can proceed, short-circuit, attach completion callbacks, or handle
     * exceptions — both synchronously (start phase) and asynchronously (end phase).</p>
     *
     * @return the layer's async around-advice, never {@code null}
     */
    AsyncLayerAction<Void, R> asyncLayerAction();

    /**
     * Returns {@code true} if this async layer should be included in the pipeline
     * for the given method.
     *
     * <p>Implementations typically check the method's return type, annotations,
     * or declaring interface to decide.</p>
     *
     * <p><strong>Important difference from {@link AspectLayerProvider#canHandle}:</strong>
     * the default implementation here checks whether the method returns a
     * {@link java.util.concurrent.CompletionStage}, because async layers are
     * inherently incompatible with synchronous return types. Override this
     * method to further restrict (or relax) the filtering.</p>
     *
     * @param method the service method being invoked
     * @return {@code true} if this layer should handle the method; the default
     * returns {@code true} only for methods returning {@link CompletionStage}
     */
    default boolean canHandle(Method method) {
        return CompletionStage.class.isAssignableFrom(method.getReturnType());
    }
}
