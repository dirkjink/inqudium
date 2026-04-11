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
 * <p>When a {@link Method} is provided, the async pipeline structure (provider
 * filtering, sorting, and {@link eu.inqudium.imperative.core.pipeline.AsyncLayerAction}
 * chain composition) is resolved <strong>once</strong> per method and cached in a
 * {@link ConcurrentHashMap}. Subsequent calls for the same method reuse the
 * pre-composed chain — only the terminal executor ({@code pjp::proceed}) is
 * created per invocation.</p>
 *
 * <p>This mirrors the optimization in {@link AbstractPipelineAspect} and follows
 * the same pattern as {@code SyncDispatchExtension} in the proxy module.</p>
 *
 * <h3>Two-phase execution</h3>
 * <p>Each async layer has a synchronous start phase (before
 * {@code next.executeAsync()}) and an asynchronous end phase (attached via
 * {@code whenComplete()}, {@code thenApply()}, etc.). The pre-composed chain
 * preserves this two-phase semantics.</p>
 *
 * <h3>Exception transport</h3>
 * <p>Checked exceptions from the synchronous start phase are transported via
 * {@link java.util.concurrent.CompletionException} and unwrapped by
 * {@link AsyncResolvedPipeline#execute(JoinPointExecutor)}. Exceptions during
 * the async completion phase surface through the returned
 * {@link CompletionStage}.</p>
 *
 * <h3>Introspection</h3>
 * <p>For diagnostic and testing purposes, the {@link #buildAsyncPipeline} methods
 * still construct a full {@link AsyncJoinPointWrapper} chain with
 * {@link eu.inqudium.core.pipeline.Wrapper} introspection support. These are not
 * used on the hot path.</p>
 */
public abstract class AbstractAsyncPipelineAspect {

    /**
     * Cache of pre-composed async pipelines, keyed by the target {@link Method}.
     */
    private final ConcurrentHashMap<Method, AsyncResolvedPipeline> pipelineCache =
            new ConcurrentHashMap<>();

    /**
     * Returns the ordered list of async layer providers for this aspect.
     *
     * <p>Called once per unique {@link Method} encountered — the result is used
     * to build an {@link AsyncResolvedPipeline} that is then cached. Subsequent
     * calls for the same method do not invoke this method again.</p>
     *
     * <p>Implementations should return quickly and without side effects.
     * Returning a pre-built, immutable list is strongly recommended.</p>
     *
     * @return the async layer providers, never {@code null}
     */
    protected abstract List<AsyncAspectLayerProvider<Object>> asyncLayerProviders();

    // ======================== Hot path: AsyncResolvedPipeline ========================

    /**
     * Executes the given join point through a cached, pre-composed async pipeline
     * filtered by the target method.
     *
     * <p>On first invocation for a given method, the pipeline is resolved and
     * cached. On subsequent invocations, the cached pipeline is reused — only
     * the terminal executor is created per call.</p>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed}),
     *                     expected to return a {@link CompletionStage}
     * @param method       the target method, used as cache key and for
     *                     {@code canHandle} filtering on first resolution
     * @return a {@link CompletionStage} carrying the result
     * @throws Throwable any synchronous exception from the start phase
     */
    protected CompletionStage<Object> executeThroughAsync(
            JoinPointExecutor<Object> coreExecutor, Method method) throws Throwable {
        return resolveAsyncPipeline(method).execute(coreExecutor);
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
     * Resolves (or retrieves from cache) the async pipeline for the given method.
     *
     * <p>{@link #asyncLayerProviders()} is called <strong>before</strong> entering
     * {@link ConcurrentHashMap#computeIfAbsent}, so the subclass implementation
     * never runs inside the map's bucket lock. On concurrent first-access for the
     * same method, multiple threads may call {@code asyncLayerProviders()} redundantly,
     * but {@code computeIfAbsent} guarantees that only one {@code AsyncResolvedPipeline}
     * is created and cached.</p>
     */
    private AsyncResolvedPipeline resolveAsyncPipeline(Method method) {
        // Fast path: already cached — no locking, no asyncLayerProviders() call
        AsyncResolvedPipeline pipeline = pipelineCache.get(method);
        if (pipeline != null) {
            return pipeline;
        }

        // Slow path: resolve providers OUTSIDE computeIfAbsent to avoid
        // calling potentially expensive subclass code inside the bucket lock
        List<AsyncAspectLayerProvider<Object>> providers = asyncLayerProviders();

        return pipelineCache.computeIfAbsent(
                method, m -> AsyncResolvedPipeline.resolve(providers, m));
    }

    // ======================== Non-cached convenience methods ========================

    /**
     * Builds an async wrapper chain from all providers and executes it.
     *
     * <p>This method does <strong>not</strong> use the pipeline cache because
     * no {@link Method} key is available. Each call builds a fresh chain.
     * Prefer {@link #executeThroughAsync(JoinPointExecutor, Method)} on hot paths.</p>
     *
     * @param coreExecutor the join point execution, expected to return a
     *                     {@link CompletionStage}
     * @return a {@link CompletionStage} carrying the result
     * @throws Throwable any synchronous exception from the start phase
     */
    @SuppressWarnings("unchecked")
    protected CompletionStage<Object> executeThroughAsync(
            JoinPointExecutor<Object> coreExecutor) throws Throwable {

        JoinPointExecutor<CompletionStage<Object>> typedExecutor =
                () -> (CompletionStage<Object>) coreExecutor.proceed();

        AsyncJoinPointWrapper<Object> chain = new AsyncAspectPipelineBuilder<Object>()
                .addProviders(asyncLayerProviders())
                .buildChain(typedExecutor);

        return chain.proceed();
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
                .addProviders(asyncLayerProviders())
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
                .addProviders(asyncLayerProviders(), method)
                .buildChain(coreExecutor);
    }
}
