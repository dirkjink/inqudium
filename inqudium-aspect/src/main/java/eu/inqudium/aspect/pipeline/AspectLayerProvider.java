package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;

/**
 * Defines a single cross-cutting layer that can be contributed to an aspect's
 * wrapper pipeline.
 *
 * <p>Implementations represent one concern (logging, timing, bulkhead, retry, etc.)
 * and are collected by the aspect infrastructure to build a {@link eu.inqudium.core.pipeline.JoinPointWrapper}
 * chain in priority order.</p>
 *
 * <h3>Ordering</h3>
 * <p>Layers with lower {@link #order()} values become the outermost wrappers in the chain.
 * For example, a logging layer with {@code order() = 10} wraps a timing layer with
 * {@code order() = 20}, which wraps a retry layer with {@code order() = 30}.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * public class TimingLayerProvider implements AspectLayerProvider<Object> {
 *     @Override public String layerName() { return "TIMING"; }
 *     @Override public int order() { return 20; }
 *
 *     @Override
 *     public LayerAction<Void, Object> layerAction() {
 *         return (chainId, callId, arg, next) -> {
 *             long start = System.nanoTime();
 *             try {
 *                 return next.execute(chainId, callId, arg);
 *             } finally {
 *                 metrics.record(System.nanoTime() - start);
 *             }
 *         };
 *     }
 * }
 * }</pre>
 *
 * @param <R> the return type of the proxied method
 */
public interface AspectLayerProvider<R> {

    /**
     * Returns a human-readable name for this layer, used in diagnostics
     * and {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()} output.
     *
     * <p>Convention: uppercase element type, e.g. {@code "LOGGING"}, {@code "TIMING"},
     * {@code "BULKHEAD(pool-A)"}.</p>
     *
     * @return a descriptive, non-null layer name
     */
    String layerName();

    /**
     * Returns the priority of this layer within the pipeline.
     *
     * <p>Lower values produce outermost layers. Layers with equal order values
     * retain their registration order (stable sort).</p>
     *
     * @return an integer priority value
     */
    int order();

    /**
     * Returns the around-advice logic for this layer.
     *
     * <p>The returned {@link LayerAction} has full control over the invocation flow:
     * it can proceed, short-circuit, retry, modify arguments or results, or handle
     * exceptions — exactly like any other layer in the wrapper pipeline.</p>
     *
     * @return the layer's around-advice, never {@code null}
     */
    LayerAction<Void, R> layerAction();

    /**
     * Returns {@code true} if this layer should be included in the pipeline
     * for the given method.
     *
     * <p>Implementations typically check the method's return type, annotations,
     * or declaring interface to decide. For example, an async-only layer might
     * return {@code false} for methods that do not return a
     * {@link java.util.concurrent.CompletionStage}.</p>
     *
     * <p>The default implementation returns {@code true}, meaning the layer
     * applies to all methods. Override this to restrict a layer to specific
     * method signatures or annotations.</p>
     *
     * @param method the service method being invoked
     * @return {@code true} if this layer should handle the method
     */
    default boolean canHandle(Method method) {
        return true;
    }
}
