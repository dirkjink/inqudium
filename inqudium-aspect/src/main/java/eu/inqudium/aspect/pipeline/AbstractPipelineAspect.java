package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base class for AspectJ aspects that execute method calls through a
 * wrapper pipeline.
 *
 * <p>Subclasses provide the list of {@link AspectLayerProvider}s; this class
 * assembles and executes the pipeline.</p>
 *
 * <h3>Pipeline caching</h3>
 * <p>When a {@link Method} is provided, the pipeline structure (provider filtering,
 * sorting, and {@link eu.inqudium.core.pipeline.LayerAction} chain composition) is
 * resolved <strong>once</strong> per method and cached in a {@link ConcurrentHashMap}.
 * Subsequent calls for the same method reuse the pre-composed chain — only the
 * terminal executor ({@code pjp::proceed}) is created per invocation.</p>
 *
 * <p>This mirrors the optimization used by {@code SyncDispatchExtension} in the
 * proxy module: the chain structure is fixed at resolution time, and the per-call
 * cost is reduced to a single lambda creation plus the chain traversal itself.</p>
 *
 * <h3>Introspection</h3>
 * <p>For diagnostic and testing purposes, the {@link #buildPipeline} methods still
 * construct a full {@link JoinPointWrapper} chain with {@link eu.inqudium.core.pipeline.Wrapper}
 * introspection support ({@code inner()}, {@code chainId()}, {@code toStringHierarchy()}).
 * These are not used on the hot path.</p>
 *
 * <h3>Exception transport</h3>
 * <p>Checked exceptions from the proxied method are transported through the chain
 * via {@link java.util.concurrent.CompletionException} and unwrapped by
 * {@link ResolvedPipeline#execute(JoinPointExecutor)}. The caller sees the original
 * throwable type, preserving the proxied method's exception contract.</p>
 */
public abstract class AbstractPipelineAspect {

    /**
     * Cache of pre-composed pipelines, keyed by the target {@link Method}.
     *
     * <p>Populated lazily on first access per method via
     * {@link ConcurrentHashMap#computeIfAbsent}. The cached
     * {@link ResolvedPipeline} is immutable and thread-safe, so no further
     * synchronization is needed after initial resolution.</p>
     */
    private final ConcurrentHashMap<Method, ResolvedPipeline> pipelineCache =
            new ConcurrentHashMap<>();

    /**
     * Returns the ordered list of layer providers for this aspect.
     *
     * <p>Providers are sorted by {@link AspectLayerProvider#order()} during
     * chain assembly. Implementations may return a cached, immutable list
     * if the layer configuration is static.</p>
     *
     * @return the layer providers to assemble into the pipeline, never {@code null}
     */
    protected abstract List<AspectLayerProvider<Object>> layerProviders();

    // ======================== Hot path: ResolvedPipeline ========================

    /**
     * Executes the given join point through a cached, pre-composed pipeline
     * filtered by the target method.
     *
     * <p>On first invocation for a given method, the pipeline is resolved
     * (providers filtered by {@code canHandle}, sorted, and composed into a
     * chain factory). On subsequent invocations, the cached pipeline is reused —
     * only the terminal executor is created per call.</p>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed})
     * @param method       the target method, used as cache key and for
     *                     {@code canHandle} filtering on first resolution
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    protected Object executeThrough(JoinPointExecutor<Object> coreExecutor,
                                    Method method) throws Throwable {
        ResolvedPipeline pipeline = pipelineCache.computeIfAbsent(
                method, m -> ResolvedPipeline.resolve(layerProviders(), m));

        return pipeline.execute(coreExecutor);
    }

    /**
     * Returns the cached {@link ResolvedPipeline} for the given method,
     * resolving it on first access.
     *
     * <p>Useful for diagnostics — the returned pipeline exposes
     * {@link ResolvedPipeline#layerNames()}, {@link ResolvedPipeline#depth()},
     * {@link ResolvedPipeline#chainId()}, and
     * {@link ResolvedPipeline#toStringHierarchy()}.</p>
     *
     * @param method the target method
     * @return the pre-composed pipeline for the method
     */
    protected ResolvedPipeline resolvedPipeline(Method method) {
        return pipelineCache.computeIfAbsent(
                method, m -> ResolvedPipeline.resolve(layerProviders(), m));
    }

    // ======================== Non-cached convenience methods ========================

    /**
     * Builds a wrapper chain from all providers and executes it.
     *
     * <p>This method does <strong>not</strong> use the pipeline cache because
     * no {@link Method} key is available. Each call builds a fresh chain.
     * Prefer the {@link #executeThrough(JoinPointExecutor, Method)} overload
     * on hot paths.</p>
     *
     * @param coreExecutor the join point execution
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    protected Object executeThrough(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        JoinPointWrapper<Object> chain = new AspectPipelineBuilder<Object>()
                .addProviders(layerProviders())
                .buildChain(coreExecutor);

        return chain.proceed();
    }

    // ======================== Introspection (cold path) ========================

    /**
     * Builds a full {@link JoinPointWrapper} chain without executing it.
     *
     * <p>Returns a chain with full {@link eu.inqudium.core.pipeline.Wrapper}
     * introspection ({@code inner()}, {@code chainId()}, {@code toStringHierarchy()}).
     * Intended for diagnostics and testing — not for hot-path execution.</p>
     *
     * @param coreExecutor the join point execution
     * @return the outermost wrapper of the assembled chain
     */
    protected JoinPointWrapper<Object> buildPipeline(JoinPointExecutor<Object> coreExecutor) {
        return new AspectPipelineBuilder<Object>()
                .addProviders(layerProviders())
                .buildChain(coreExecutor);
    }

    /**
     * Builds a full {@link JoinPointWrapper} chain filtered by the target method,
     * without executing it.
     *
     * <p>Intended for diagnostics and testing — not for hot-path execution.
     * For hot-path use, prefer {@link #executeThrough(JoinPointExecutor, Method)}
     * which uses the cached {@link ResolvedPipeline}.</p>
     *
     * @param coreExecutor the join point execution
     * @param method       the target method, used to filter providers
     * @return the outermost wrapper of the assembled chain
     */
    protected JoinPointWrapper<Object> buildPipeline(JoinPointExecutor<Object> coreExecutor,
                                                     Method method) {
        return new AspectPipelineBuilder<Object>()
                .addProviders(layerProviders(), method)
                .buildChain(coreExecutor);
    }
}
