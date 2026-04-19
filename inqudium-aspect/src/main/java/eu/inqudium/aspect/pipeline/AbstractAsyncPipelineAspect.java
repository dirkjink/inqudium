package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.AsyncJoinPointWrapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;

/**
 * Abstract base class for AspectJ aspects that execute method calls through
 * an asynchronous wrapper pipeline.
 *
 * <p>The async counterpart to {@link AbstractPipelineAspect}. Subclasses provide
 * the list of {@link AsyncAspectLayerProvider}s; this class assembles and executes
 * the pipeline.</p>
 *
 * <h3>Pipeline caching</h3>
 * <p>Two caches are maintained:</p>
 * <ul>
 *   <li>A per-{@link Method} cache of {@link AsyncResolvedPipeline}s for the
 *       method-filtered hot path —
 *       {@link #executeThroughAsync(JoinPointExecutor, Method)}.</li>
 *   <li>A single unfiltered async pipeline used by
 *       {@link #executeThroughAsync(JoinPointExecutor)} when no method is
 *       available. Resolved lazily on first access via double-checked locking.
 *       This preserves the array-based composition of
 *       {@link AsyncResolvedPipeline} even when no {@code canHandle} filtering
 *       applies — avoiding per-call {@link AsyncAspectPipelineBuilder} +
 *       {@link AsyncJoinPointWrapper} allocation.</li>
 * </ul>
 *
 * <h3>Two-phase execution</h3>
 * <p>Each async layer has a synchronous start phase (before
 * {@code next.executeAsync()}) and an asynchronous end phase (attached via
 * {@code whenComplete()}, {@code thenApply()}, etc.).</p>
 *
 * <h3>Exception transport</h3>
 * <p>Checked exceptions from the synchronous start phase are transported via
 * {@link java.util.concurrent.CompletionException} and unwrapped by
 * {@link AsyncResolvedPipeline#execute(JoinPointExecutor)}. Exceptions during
 * the async completion phase surface through the returned
 * {@link CompletionStage}.</p>
 */
public abstract class AbstractAsyncPipelineAspect {

    /**
     * Cache of pre-composed, method-filtered async pipelines, keyed by the
     * target {@link Method}.
     */
    private final ConcurrentHashMap<Method, AsyncResolvedPipeline> pipelineCache =
            new ConcurrentHashMap<>();

    /**
     * Lazily initialized, immutable snapshot of the provider list returned by
     * {@link #asyncLayerProviders()}. Captured exactly once on first access
     * via double-checked locking.
     */
    private volatile List<AsyncAspectLayerProvider<Object>> cachedProviders;

    /**
     * Lazily resolved, unfiltered async pipeline — used by
     * {@link #executeThroughAsync(JoinPointExecutor)} when no target method
     * is available. Initialized once via double-checked locking.
     */
    private volatile AsyncResolvedPipeline unfilteredPipeline;

    /**
     * Returns the ordered list of async layer providers for this aspect.
     *
     * <p>Called <strong>exactly once</strong> during the lifetime of this
     * aspect instance.</p>
     *
     * @return the async layer providers, never {@code null}
     */
    protected abstract List<AsyncAspectLayerProvider<Object>> asyncLayerProviders();

    /**
     * Returns the cached provider snapshot, initializing it on first access
     * via double-checked locking.
     */
    private List<AsyncAspectLayerProvider<Object>> providers() {
        List<AsyncAspectLayerProvider<Object>> snapshot = cachedProviders;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = cachedProviders;
                if (snapshot == null) {
                    snapshot = List.copyOf(asyncLayerProviders());
                    cachedProviders = snapshot;
                }
            }
        }
        return snapshot;
    }

    // ======================== Hot path: AsyncResolvedPipeline ========================

    /**
     * Executes the given join point through a cached, pre-composed async
     * pipeline filtered by the target method.
     *
     * <p>All exceptions — including synchronous failures from a layer's start
     * phase — are delivered through the returned {@link CompletionStage},
     * never thrown directly.</p>
     *
     * @param coreExecutor the join point execution (typically
     *                     {@code () -> (CompletionStage<Object>) pjp.proceed()})
     * @param method       the target method, used as cache key and for
     *                     {@code canHandle} filtering on first resolution
     * @return a {@link CompletionStage} carrying the result or failure
     */
    protected CompletionStage<Object> executeThroughAsync(
            JoinPointExecutor<CompletionStage<Object>> coreExecutor, Method method) {
        return resolveAsyncPipeline(method).execute(coreExecutor);
    }

    /**
     * Executes the given join point through the cached unfiltered async
     * pipeline (all providers, sorted by order, no {@code canHandle} filter
     * applied).
     *
     * <p>The pipeline is resolved once per aspect instance and reused — the
     * per-call cost matches
     * {@link #executeThroughAsync(JoinPointExecutor, Method)}.</p>
     *
     * @param coreExecutor the join point execution
     * @return a {@link CompletionStage} carrying the result or failure
     */
    protected CompletionStage<Object> executeThroughAsync(
            JoinPointExecutor<CompletionStage<Object>> coreExecutor) {
        return unfilteredAsyncPipeline().execute(coreExecutor);
    }

    /**
     * Returns the cached {@link AsyncResolvedPipeline} for the given method,
     * resolving it on first access.
     *
     * @param method the target method
     * @return the pre-composed, cached async pipeline
     */
    protected AsyncResolvedPipeline resolvedAsyncPipeline(Method method) {
        return resolveAsyncPipeline(method);
    }

    /**
     * Resolves (or retrieves from cache) the async pipeline for the given
     * method.
     */
    private AsyncResolvedPipeline resolveAsyncPipeline(Method method) {
        AsyncResolvedPipeline pipeline = pipelineCache.get(method);
        if (pipeline != null) {
            return pipeline;
        }
        return pipelineCache.computeIfAbsent(method, this::createAsyncPipeline);
    }

    /**
     * Creates a new async pipeline for the given method — called at most once
     * per method via {@link ConcurrentHashMap#computeIfAbsent}.
     */
    private AsyncResolvedPipeline createAsyncPipeline(Method method) {
        return AsyncResolvedPipeline.resolve(providers(), method);
    }

    /**
     * Returns the unfiltered async pipeline, initializing it on first access
     * via double-checked locking.
     */
    private AsyncResolvedPipeline unfilteredAsyncPipeline() {
        AsyncResolvedPipeline pipeline = unfilteredPipeline;
        if (pipeline == null) {
            synchronized (this) {
                pipeline = unfilteredPipeline;
                if (pipeline == null) {
                    pipeline = AsyncResolvedPipeline.resolveAll(providers());
                    unfilteredPipeline = pipeline;
                }
            }
        }
        return pipeline;
    }

    // ======================== Introspection (cold path) ========================

    /**
     * Builds a full {@link AsyncJoinPointWrapper} chain without executing it.
     *
     * <p>Intended for diagnostics and testing — not for hot-path execution.</p>
     *
     * @param coreExecutor the async join point execution
     * @return the outermost wrapper of the assembled async chain
     */
    protected AsyncJoinPointWrapper<Object> buildAsyncPipeline(
            JoinPointExecutor<CompletionStage<Object>> coreExecutor) {
        return new AsyncAspectPipelineBuilder<Object>()
                .addProviders(providers())
                .buildChain(coreExecutor);
    }

    /**
     * Builds a full {@link AsyncJoinPointWrapper} chain filtered by the target
     * method, without executing it.
     *
     * <p>Intended for diagnostics and testing — not for hot-path execution.</p>
     *
     * @param coreExecutor the async join point execution
     * @param method       the target method, used to filter providers
     * @return the outermost wrapper of the assembled async chain
     */
    protected AsyncJoinPointWrapper<Object> buildAsyncPipeline(
            JoinPointExecutor<CompletionStage<Object>> coreExecutor, Method method) {
        return new AsyncAspectPipelineBuilder<Object>()
                .addProviders(providers(), method)
                .buildChain(coreExecutor);
    }
}
