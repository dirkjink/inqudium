package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base class for AspectJ aspects that execute method calls through a
 * wrapper pipeline.
 *
 * <p>Provides all the infrastructure a concrete aspect needs. Subclasses only
 * define the {@code @Around} pointcut and wire the production providers via
 * the constructor:</p>
 *
 * <pre>{@code
 * @Aspect
 * public class MyAspect extends AbstractPipelineAspect {
 *
 *     public MyAspect() {
 *         super(List.of(new LoggingLayerProvider(), new TimingLayerProvider()));
 *     }
 *
 *     @Around("@annotation(MyAnnotation)")
 *     public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *         return executeAround(pjp);
 *     }
 * }
 * }</pre>
 *
 * <h3>Pipeline caching</h3>
 * <p>Two caches are maintained:</p>
 * <ul>
 *   <li>A per-{@link Method} cache of {@link ResolvedPipeline}s for the
 *       {@link #executeAround executeAround} / {@link #execute(JoinPointExecutor, Method) execute(..., method)}
 *       hot path — filtered via {@link AspectLayerProvider#canHandle(Method)}.</li>
 *   <li>A single unfiltered pipeline used by {@link #execute(JoinPointExecutor)}
 *       when no method is available. Resolved lazily on first access via
 *       double-checked locking. This preserves the array-based composition
 *       of {@link ResolvedPipeline} even when no {@code canHandle} filtering
 *       applies — avoiding per-call {@link AspectPipelineBuilder} +
 *       {@link JoinPointWrapper} allocation.</li>
 * </ul>
 *
 * <h3>Concurrency contract</h3>
 * <p>The provider list is captured exactly once in an immutable snapshot using
 * double-checked locking. All pipeline resolutions use this snapshot — the
 * provider source is never called inside {@link ConcurrentHashMap#computeIfAbsent},
 * and it is guaranteed to execute at most once even under concurrent access.</p>
 *
 * <h3>Exception transport</h3>
 * <p>Checked exceptions from the proxied method are transported through the chain
 * via {@link java.util.concurrent.CompletionException} and unwrapped by
 * {@link ResolvedPipeline#execute(JoinPointExecutor)}. The caller sees the original
 * throwable type, preserving the proxied method's exception contract.</p>
 */
public abstract class AbstractPipelineAspect {

    /**
     * Cache of pre-composed, method-filtered pipelines, keyed by the target
     * {@link Method}.
     */
    private final ConcurrentHashMap<Method, ResolvedPipeline> pipelineCache =
            new ConcurrentHashMap<>();

    /**
     * The provider source — either a direct immutable list (from the constructor)
     * or lazily resolved from {@link #layerProviders()} on first access.
     */
    private volatile List<AspectLayerProvider<Object>> cachedProviders;

    /**
     * Lazily resolved, unfiltered pipeline — used by
     * {@link #execute(JoinPointExecutor)} when no target method is available.
     * Initialized once via double-checked locking.
     */
    private volatile ResolvedPipeline unfilteredPipeline;

    // ======================== Constructors ========================

    /**
     * Creates an aspect with an explicit, immutable list of providers.
     *
     * @param providers the ordered layer providers for the pipeline
     */
    protected AbstractPipelineAspect(List<AspectLayerProvider<Object>> providers) {
        this.cachedProviders = List.copyOf(providers);
    }

    /**
     * Creates an aspect that resolves providers lazily via {@link #layerProviders()}.
     *
     * <p>Subclasses using this constructor <strong>must</strong> override
     * {@link #layerProviders()}.</p>
     */
    protected AbstractPipelineAspect() {
        // cachedProviders remains null → resolved lazily via layerProviders()
    }

    // ======================== Provider resolution ========================

    /**
     * Override this method when using the no-arg constructor to provide
     * providers lazily.
     *
     * <p>Called <strong>exactly once</strong> during the lifetime of this
     * aspect instance, inside a {@code synchronized(this)} block.</p>
     *
     * @return the layer providers, never {@code null}
     * @throws UnsupportedOperationException if the no-arg constructor is used
     *         without overriding this method
     */
    protected List<AspectLayerProvider<Object>> layerProviders() {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " uses the no-arg constructor but does not "
                        + "override layerProviders(). Either pass providers via "
                        + "super(providers) or override layerProviders().");
    }

    /**
     * Returns the cached provider snapshot, initializing it on first access
     * via double-checked locking.
     */
    private List<AspectLayerProvider<Object>> providers() {
        List<AspectLayerProvider<Object>> snapshot = cachedProviders;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = cachedProviders;
                if (snapshot == null) {
                    snapshot = List.copyOf(layerProviders());
                    cachedProviders = snapshot;
                }
            }
        }
        return snapshot;
    }

    // ======================== Hot path ========================

    /**
     * Convenience entry point for {@code @Around} advice methods.
     *
     * <pre>{@code
     * @Around("@annotation(MyAnnotation)")
     * public Object around(ProceedingJoinPoint pjp) throws Throwable {
     *     return executeAround(pjp);
     * }
     * }</pre>
     *
     * @param pjp the proceeding join point provided by AspectJ
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    protected Object executeAround(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        return resolvePipeline(method).execute(pjp::proceed);
    }

    /**
     * Executes the given executor through a cached, pre-composed pipeline
     * filtered by the target method.
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed})
     * @param method       the target method, used as cache key and for
     *                     {@code canHandle} filtering on first resolution
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    public Object execute(JoinPointExecutor<Object> coreExecutor,
                          Method method) throws Throwable {
        return resolvePipeline(method).execute(coreExecutor);
    }

    /**
     * Executes the given executor through the cached unfiltered pipeline
     * (all providers, sorted by order, no {@code canHandle} filter applied).
     *
     * <p>The pipeline is resolved once per aspect instance and reused — the
     * per-call cost matches {@link #execute(JoinPointExecutor, Method)}.</p>
     *
     * @param coreExecutor the join point execution
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    public Object execute(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        return unfilteredPipeline().execute(coreExecutor);
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the cached {@link ResolvedPipeline} for the given method,
     * resolving it on first access.
     *
     * @param method the target method
     * @return the pre-composed, cached pipeline
     */
    public ResolvedPipeline getResolvedPipeline(Method method) {
        return resolvePipeline(method);
    }

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
    public JoinPointWrapper<Object> inspectPipeline(JoinPointExecutor<Object> coreExecutor) {
        return new AspectPipelineBuilder<Object>()
                .addProviders(providers())
                .buildChain(coreExecutor);
    }

    /**
     * Builds a full {@link JoinPointWrapper} chain filtered by the target method,
     * without executing it.
     *
     * @param coreExecutor the join point execution
     * @param method       the target method, used to filter providers
     * @return the outermost wrapper of the assembled chain
     */
    public JoinPointWrapper<Object> inspectPipeline(JoinPointExecutor<Object> coreExecutor,
                                                    Method method) {
        return new AspectPipelineBuilder<Object>()
                .addProviders(providers(), method)
                .buildChain(coreExecutor);
    }

    // ======================== Internal ========================

    /**
     * Resolves (or retrieves from cache) the pipeline for the given method.
     */
    private ResolvedPipeline resolvePipeline(Method method) {
        ResolvedPipeline pipeline = pipelineCache.get(method);
        if (pipeline != null) {
            return pipeline;
        }
        return pipelineCache.computeIfAbsent(method, this::createPipeline);
    }

    /**
     * Creates a new pipeline for the given method — called at most once per
     * method via {@link ConcurrentHashMap#computeIfAbsent}.
     */
    private ResolvedPipeline createPipeline(Method method) {
        return ResolvedPipeline.resolve(providers(), method);
    }

    /**
     * Returns the unfiltered pipeline, initializing it on first access via
     * double-checked locking.
     */
    private ResolvedPipeline unfilteredPipeline() {
        ResolvedPipeline pipeline = unfilteredPipeline;
        if (pipeline == null) {
            synchronized (this) {
                pipeline = unfilteredPipeline;
                if (pipeline == null) {
                    pipeline = ResolvedPipeline.resolveAll(providers());
                    unfilteredPipeline = pipeline;
                }
            }
        }
        return pipeline;
    }
}
