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
 * <p>The pipeline structure (provider filtering, sorting, and
 * {@link eu.inqudium.core.pipeline.LayerAction} chain composition) is resolved
 * <strong>once</strong> per {@link Method} and cached in a {@link ConcurrentHashMap}.
 * Subsequent calls for the same method reuse the pre-composed chain — only the
 * terminal executor ({@code pjp::proceed}) is created per invocation.</p>
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
     * Cache of pre-composed pipelines, keyed by the target {@link Method}.
     */
    private final ConcurrentHashMap<Method, ResolvedPipeline> pipelineCache =
            new ConcurrentHashMap<>();

    /**
     * The provider source — either a direct immutable list (from the constructor)
     * or lazily resolved from {@link #layerProviders()} on first access.
     */
    private volatile List<AspectLayerProvider<Object>> cachedProviders;

    // ======================== Constructors ========================

    /**
     * Creates an aspect with an explicit, immutable list of providers.
     *
     * <p>This is the preferred constructor for most aspects. The provider list
     * is captured directly — no lazy initialization, no {@code layerProviders()}
     * call needed.</p>
     *
     * @param providers the ordered layer providers for the pipeline
     */
    protected AbstractPipelineAspect(List<AspectLayerProvider<Object>> providers) {
        this.cachedProviders = List.copyOf(providers);
    }

    /**
     * Creates an aspect that resolves providers lazily via {@link #layerProviders()}.
     *
     * <p>Use this constructor when providers are not available at construction
     * time — for example, when they depend on a DI container that is initialized
     * after aspect instantiation. The provider list is resolved exactly once on
     * first access using double-checked locking.</p>
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
     * aspect instance, inside a {@code synchronized(this)} block. Safe for
     * expensive DI lookups or reflection-based discovery.</p>
     *
     * <p>Not called when using the {@link #AbstractPipelineAspect(List)}
     * constructor — the providers are captured directly.</p>
     *
     * @return the layer providers, never {@code null}
     * @throws UnsupportedOperationException if the no-arg constructor is used
     *                                       without overriding this method
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
     * <p>Extracts the {@link Method} from the {@link ProceedingJoinPoint},
     * resolves the cached pipeline, and executes through it. This is the
     * one-liner that concrete aspects call from their {@code @Around} method:</p>
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
        if (!(pjp.getSignature() instanceof MethodSignature methodSignature)) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " received a non-method join point "
                            + "(signature type: " + pjp.getSignature().getClass().getName()
                            + "). Ensure the @Around pointcut only matches method executions.");
        }
        Method method = methodSignature.getMethod();
        if (method == null) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " received a MethodSignature with a null "
                            + "Method for join point: " + pjp.getSignature().toLongString()
                            + ". This may indicate a synthetic or unresolvable method.");
        }
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
     * Executes the given executor through a fresh pipeline built from all
     * providers (no method filtering, no caching).
     *
     * <p><strong>Not suitable for hot paths</strong> — builds a fresh pipeline on
     * every call. Prefer {@link #execute(JoinPointExecutor, Method)} for
     * production use.</p>
     *
     * @param coreExecutor the join point execution
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    protected Object execute(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        return new AspectPipelineBuilder<Object>()
                .addProviders(providers())
                .buildChain(coreExecutor)
                .proceed();
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the cached {@link ResolvedPipeline} for the given method,
     * resolving it on first access.
     *
     * <p>The returned pipeline exposes {@link ResolvedPipeline#layerNames()},
     * {@link ResolvedPipeline#depth()}, {@link ResolvedPipeline#chainId()},
     * and {@link ResolvedPipeline#toStringHierarchy()}.</p>
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
     *
     * <p><strong>Trade-off note:</strong> Between the fast-path {@code get()} miss
     * and the {@code computeIfAbsent}, another thread may have already populated
     * the entry. In that case, {@code providers()} is called "unnecessarily" —
     * but after first initialization it is merely a volatile read (~1ns), which
     * is cheaper than restructuring to avoid it. Calling {@code providers()}
     * outside of {@code computeIfAbsent} ensures no subclass code
     * ({@link #layerProviders()}) ever runs inside a CHM bucket lock.</p>
     */
    private ResolvedPipeline resolvePipeline(Method method) {
        ResolvedPipeline pipeline = pipelineCache.get(method);
        if (pipeline != null) {
            return pipeline;
        }

        List<AspectLayerProvider<Object>> providers = providers();
        return pipelineCache.computeIfAbsent(
                method, m -> ResolvedPipeline.resolve(providers, m));
    }
}
