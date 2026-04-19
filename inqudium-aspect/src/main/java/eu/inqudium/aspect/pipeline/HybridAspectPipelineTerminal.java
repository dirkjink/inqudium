package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Hybrid AspectJ terminal that automatically dispatches sync and async
 * method calls through the appropriate pipeline chain.
 *
 * <p>When the intercepted method returns {@link CompletionStage}, the call
 * is routed through the async chain (elements use
 * {@link InqAsyncDecorator#decorateAsyncJoinPoint}, resource lifecycle tied
 * to stage completion). All other methods use the sync chain.</p>
 *
 * <h3>Per-Method caching</h3>
 * <p>The sync/async decision and the decorator chain factory are resolved
 * <strong>once per {@link Method}</strong> and cached in a
 * {@link ConcurrentHashMap}. Subsequent invocations of the same method
 * only create the terminal lambda ({@code pjp::proceed}) and apply the
 * pre-composed chain factory — no iteration, no sorting, no
 * {@code isAssignableFrom} check on the hot path.</p>
 *
 * <pre>
 *   First call to placeOrder():
 *     1. MethodSignature.getMethod()                         ← per call
 *     2. cache miss → method.getReturnType() → sync          ← once
 *     3. pipeline.chain(identity, fold) → chainFactory        ← once
 *     4. factory.apply(pjp::proceed)                          ← per call
 *     5. chain.proceed()                                      ← per call
 *
 *   Subsequent calls to placeOrder():
 *     1. MethodSignature.getMethod()                          ← per call
 *     2. cache hit → CachedChain.sync(factory)                ← fast path
 *     3. factory.apply(pjp::proceed)                          ← per call
 *     4. chain.proceed()                                      ← per call
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Aspect
 * public class ResilienceAspect {
 *
 *     private final HybridAspectPipelineTerminal terminal =
 *             HybridAspectPipelineTerminal.of(
 *                     InqPipeline.builder()
 *                             .shield(circuitBreaker)
 *                             .shield(bulkhead)
 *                             .build());
 *
 *     @Around("@annotation(Resilient)")
 *     public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *         return terminal.executeAround(pjp);
 *     }
 * }
 * }</pre>
 *
 * <h3>Element requirements</h3>
 * <p>Elements must implement <strong>both</strong>
 * {@link InqDecorator} (sync) and {@link InqAsyncDecorator} (async).</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The per-Method
 * cache uses {@link ConcurrentHashMap}.</p>
 *
 * @since 0.8.0
 */
public final class HybridAspectPipelineTerminal {

    private final InqPipeline pipeline;

    /**
     * Pre-composed chain factories, cached per Method.
     */
    private final ConcurrentHashMap<Method, CachedChain> chainCache =
            new ConcurrentHashMap<>();

    private HybridAspectPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Creates a hybrid aspect terminal for the given pipeline.
     *
     * @param pipeline the composed pipeline
     * @return the hybrid terminal
     * @throws NullPointerException if pipeline is null
     */
    public static HybridAspectPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new HybridAspectPipelineTerminal(pipeline);
    }

    /**
     * Returns the underlying pipeline.
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    // ======================== AspectJ execution ========================

    /**
     * Executes the given {@link ProceedingJoinPoint} through the pipeline,
     * using the cached chain factory for the intercepted method.
     *
     * <ul>
     *   <li>Return type is {@link CompletionStage} → async chain
     *       (uniform error channel — never throws)</li>
     *   <li>All other return types → sync chain</li>
     * </ul>
     *
     * @param pjp the proceeding join point provided by AspectJ
     * @return the result — either a direct value (sync) or a
     *         {@link CompletionStage} (async)
     * @throws Throwable any exception from sync methods or pipeline elements
     */
    @SuppressWarnings("unchecked")
    public Object executeAround(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        CachedChain cached = resolveChain(method);

        if (cached.async) {
            try {
                JoinPointExecutor<CompletionStage<Object>> terminal =
                        () -> (CompletionStage<Object>) pjp.proceed();
                return cached.asyncFactory.apply(terminal).proceed();
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        } else {
            JoinPointExecutor<Object> terminal = pjp::proceed;
            return cached.syncFactory.apply(terminal).proceed();
        }
    }

    // ======================== Generic execution ========================

    /**
     * Executes a sync call through the pipeline (uncached).
     * Useful for unit tests without AspectJ weaving.
     */
    @SuppressWarnings("unchecked")
    public <R> R execute(JoinPointExecutor<R> executor) throws Throwable {
        Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> factory =
                buildSyncChainFactory();
        return (R) factory.apply((JoinPointExecutor<Object>) (JoinPointExecutor<?>) executor).proceed();
    }

    /**
     * Executes an async call through the pipeline (uncached).
     * Useful for unit tests without AspectJ weaving. Never throws.
     */
    @SuppressWarnings("unchecked")
    public <R> CompletionStage<R> executeAsync(
            JoinPointExecutor<CompletionStage<R>> executor) {
        try {
            Function<JoinPointExecutor<CompletionStage<Object>>,
                    JoinPointExecutor<CompletionStage<Object>>> factory =
                    buildAsyncChainFactory();
            return (CompletionStage<R>) factory
                    .apply((JoinPointExecutor<CompletionStage<Object>>) (JoinPointExecutor<?>) executor)
                    .proceed();
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ======================== Internal: caching ========================

    /**
     * Resolves the cached chain factory for the given method, building it
     * on first access.
     */
    private CachedChain resolveChain(Method method) {
        CachedChain cached = chainCache.get(method);
        if (cached != null) {
            return cached;
        }
        return chainCache.computeIfAbsent(method, this::buildCachedChain);
    }

    /**
     * Builds a {@link CachedChain} for the given method: determines sync
     * vs async based on return type, and pre-composes the chain factory.
     */
    private CachedChain buildCachedChain(Method method) {
        if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            return CachedChain.async(buildAsyncChainFactory());
        } else {
            return CachedChain.sync(buildSyncChainFactory());
        }
    }

    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> buildSyncChainFactory() {
        return pipeline.chain(
                Function.<JoinPointExecutor<Object>>identity(),
                (accFn, element) -> executor ->
                        ((InqDecorator<Void, Object>) asDecorator(element))
                                .decorateJoinPoint(accFn.apply(executor)));
    }

    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>> buildAsyncChainFactory() {
        return pipeline.chain(
                Function.<JoinPointExecutor<CompletionStage<Object>>>identity(),
                (accFn, element) -> executor ->
                        ((InqAsyncDecorator<Void, Object>) asAsyncDecorator(element))
                                .decorateAsyncJoinPoint(accFn.apply(executor)));
    }

    // ======================== Internal: casting ========================

    private static InqDecorator<?, ?> asDecorator(InqElement element) {
        if (element instanceof InqDecorator<?, ?> d) return d;
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqDecorator. "
                        + "HybridAspectPipelineTerminal requires all elements to implement "
                        + "InqDecorator for sync methods.");
    }

    private static InqAsyncDecorator<?, ?> asAsyncDecorator(InqElement element) {
        if (element instanceof InqAsyncDecorator<?, ?> d) return d;
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqAsyncDecorator. "
                        + "HybridAspectPipelineTerminal requires all elements to implement "
                        + "InqAsyncDecorator for async methods (returning CompletionStage).");
    }

    // ======================== Internal: cached chain holder ========================

    /**
     * Holds the pre-composed chain factory for a single {@link Method}.
     * Immutable after construction. The sync/async decision is stored in
     * the {@code async} flag; only the relevant factory field is populated.
     */
    private static final class CachedChain {

        final boolean async;
        final Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> syncFactory;
        final Function<JoinPointExecutor<CompletionStage<Object>>,
                JoinPointExecutor<CompletionStage<Object>>> asyncFactory;

        private CachedChain(
                boolean async,
                Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> syncFactory,
                Function<JoinPointExecutor<CompletionStage<Object>>,
                        JoinPointExecutor<CompletionStage<Object>>> asyncFactory) {
            this.async = async;
            this.syncFactory = syncFactory;
            this.asyncFactory = asyncFactory;
        }

        static CachedChain sync(
                Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> factory) {
            return new CachedChain(false, factory, null);
        }

        static CachedChain async(
                Function<JoinPointExecutor<CompletionStage<Object>>,
                        JoinPointExecutor<CompletionStage<Object>>> factory) {
            return new CachedChain(true, null, factory);
        }
    }
}
