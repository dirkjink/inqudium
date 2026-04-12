package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.AsyncJoinPointWrapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
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
     * Lazily initialized, immutable snapshot of the provider list returned by
     * {@link #asyncLayerProviders()}. Captured once on first access and reused
     * for all subsequent pipeline resolutions.
     */
    private volatile List<AsyncAspectLayerProvider<Object>> cachedProviders;

    /**
     * Returns the ordered list of async layer providers for this aspect.
     *
     * <p>Called <strong>exactly once</strong> during the lifetime of this aspect
     * instance — the result is captured in an immutable snapshot and reused for
     * all pipeline resolutions.</p>
     *
     * <p>Implementations may return a new list on each call (the framework
     * handles deduplication), but returning a pre-built, immutable list is
     * recommended for clarity.</p>
     *
     * @return the async layer providers, never {@code null}
     */
    protected abstract List<AsyncAspectLayerProvider<Object>> asyncLayerProviders();

    /**
     * Returns the cached provider snapshot, initializing it on first access
     * via double-checked locking.
     *
     * <p>Guarantees that {@link #asyncLayerProviders()} is called exactly once,
     * even under concurrent access — matching the contract documented on that
     * method.</p>
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
     * Executes the given join point through a cached, pre-composed async pipeline
     * filtered by the target method.
     *
     * <p>On first invocation for a given method, the pipeline is resolved and
     * cached. On subsequent invocations, the cached pipeline is reused — only
     * the terminal executor is created per call.</p>
     *
     * <p>All exceptions — including synchronous failures from a layer's start
     * phase — are delivered through the returned {@link CompletionStage}, never
     * thrown directly. This provides a uniform error channel for callers.</p>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed}),
     *                     expected to return a {@link CompletionStage}
     * @param method       the target method, used as cache key and for
     *                     {@code canHandle} filtering on first resolution
     * @return a {@link CompletionStage} carrying the result or failure
     */
    protected CompletionStage<Object> executeThroughAsync(
            JoinPointExecutor<Object> coreExecutor, Method method) {
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
     * <p>Uses the cached provider snapshot from {@link #providers()}, which is
     * resolved once per aspect lifetime. The snapshot is then passed into
     * {@link ConcurrentHashMap#computeIfAbsent} — no subclass code runs inside
     * the map's bucket lock.</p>
     */
    private AsyncResolvedPipeline resolveAsyncPipeline(Method method) {
        // Fast path: already cached — no locking, no provider access
        AsyncResolvedPipeline pipeline = pipelineCache.get(method);
        if (pipeline != null) {
            return pipeline;
        }

        // Slow path: use the cached provider snapshot (resolved once per
        // aspect lifetime), then atomically populate the pipeline cache
        List<AsyncAspectLayerProvider<Object>> providers = providers();

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
     * <p>Like the hot path, all exceptions are delivered through the returned
     * {@link CompletionStage}, never thrown directly.</p>
     *
     * @param coreExecutor the join point execution, expected to return a
     *                     {@link CompletionStage}
     * @return a {@link CompletionStage} carrying the result or failure
     */
    @SuppressWarnings("unchecked")
    protected CompletionStage<Object> executeThroughAsync(
            JoinPointExecutor<Object> coreExecutor) {

        try {
            JoinPointExecutor<CompletionStage<Object>> typedExecutor = () -> {
                Object result = coreExecutor.proceed();
                if (result instanceof CompletionStage<?> stage) {
                    return (CompletionStage<Object>) stage;
                }
                throw new IllegalStateException(
                        "AsyncPipelineAspect expected the proxied method to return a "
                                + "CompletionStage, but received: "
                                + (result == null ? "null" : result.getClass().getName())
                                + ". Ensure this aspect is only applied to methods returning "
                                + "CompletionStage or CompletableFuture.");
            };

            AsyncJoinPointWrapper<Object> chain = new AsyncAspectPipelineBuilder<Object>()
                    .addProviders(providers())
                    .buildChain(typedExecutor);

            return chain.proceed();
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
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
