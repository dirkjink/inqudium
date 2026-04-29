package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.proxy.MethodHandleCache;
import eu.inqudium.core.pipeline.proxy.MethodInvoker;
import eu.inqudium.core.pipeline.proxy.ProxyInvocationSupport;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Hybrid dynamic proxy terminal that automatically dispatches sync and
 * async method calls through the appropriate pipeline chain.
 *
 * <p>When a proxied method returns {@link CompletionStage}, the call is
 * routed through the async chain (elements use
 * {@link InqAsyncDecorator#decorateAsyncJoinPoint}, resource lifecycle tied
 * to stage completion). All other methods use the sync chain.</p>
 *
 * <h3>Per-Method caching</h3>
 * <p>Three independent static-per-method computations are resolved
 * <strong>once per {@link Method}</strong> and stored in a single
 * {@link CachedChain} entry:</p>
 * <ol>
 *   <li>The sync-vs-async decision (based on the method's return type).</li>
 *   <li>The pre-composed decorator chain factory for that dispatch mode.</li>
 *   <li>A pre-built {@link MethodInvoker} that holds the arity-specialized
 *       {@link java.lang.invoke.MethodHandle} invocation path for the target
 *       method — eliminating the reflection overhead of {@code Method.invoke()}
 *       and the {@link java.lang.reflect.InvocationTargetException} wrap/unwrap
 *       dance from the hot path.</li>
 * </ol>
 *
 * <pre>
 *   First call to placeOrder():
 *     1. method.getReturnType() → String → sync                 ← once
 *     2. pipeline.chain(identity, fold) → chainFactory           ← once
 *     3. handleCache.resolveInvoker(method) → MethodInvoker      ← once
 *     4. cache.put(placeOrder, CachedChain.sync(factory, invoker))
 *     5. factory.apply(() → invoker.invoke(target, args))        ← per call
 *     6. chain.proceed()                                          ← per call
 *
 *   Subsequent calls to placeOrder():
 *     1. cache.get(placeOrder)                                    ← fast path
 *     2. factory.apply(() → invoker.invoke(target, args))         ← per call
 *     3. chain.proceed()                                          ← per call
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqPipeline pipeline = InqPipeline.builder()
 *         .shield(circuitBreaker)
 *         .shield(bulkhead)
 *         .build();
 *
 * HybridProxyPipelineTerminal terminal = HybridProxyPipelineTerminal.of(pipeline);
 * MyService proxy = terminal.protect(MyService.class, realService);
 *
 * proxy.findById(42);           // sync chain
 * proxy.findByIdAsync(42);      // async chain (CompletionStage return type)
 * }</pre>
 *
 * <h3>Element requirements</h3>
 * <p>Elements must implement <strong>both</strong>
 * {@link InqDecorator} (sync) and {@link InqAsyncDecorator} (async).
 * A {@link ClassCastException} with a descriptive message is thrown at
 * chain-build time if an element is missing the required interface.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances and created proxies are safe for concurrent use. The
 * per-Method cache uses {@link ConcurrentHashMap} — concurrent first-access
 * to the same method may redundantly build the {@link CachedChain} (benign
 * race), but the result is always identical and immutable.</p>
 *
 * @since 0.8.0
 */
public final class HybridProxyPipelineTerminal {

    private final InqPipeline pipeline;

    /**
     * Per-instance handle cache. Each method dispatched through this
     * terminal has its invoker resolved here exactly once.
     */
    private final MethodHandleCache handleCache = new MethodHandleCache();

    /**
     * Pre-composed chain factories + invokers, cached per Method. The fast
     * path (after first access) is a single {@code ConcurrentHashMap.get()}
     * and a direct call through the cached invoker and factory.
     */
    private final ConcurrentHashMap<Method, CachedChain> chainCache =
            new ConcurrentHashMap<>();

    private HybridProxyPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Creates a hybrid proxy terminal for the given pipeline.
     *
     * @param pipeline the composed pipeline
     * @return the hybrid terminal
     * @throws NullPointerException if pipeline is null
     */
    public static HybridProxyPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new HybridProxyPipelineTerminal(pipeline);
    }

    private static InqDecorator<?, ?> asDecorator(InqElement element) {
        if (element instanceof InqDecorator<?, ?> d) return d;
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.name()
                        + "', type=" + element.elementType()
                        + ") does not implement InqDecorator. "
                        + "HybridProxyPipelineTerminal requires all elements to implement "
                        + "InqDecorator for sync methods.");
    }

    private static InqAsyncDecorator<?, ?> asAsyncDecorator(InqElement element) {
        if (element instanceof InqAsyncDecorator<?, ?> d) return d;
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.name()
                        + "', type=" + element.elementType()
                        + ") does not implement InqAsyncDecorator. "
                        + "HybridProxyPipelineTerminal requires all elements to implement "
                        + "InqAsyncDecorator for async methods (returning CompletionStage).");
    }

    // ======================== Proxy creation ========================

    /**
     * Returns the underlying pipeline.
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    /**
     * Creates a JDK dynamic proxy that routes sync and async method calls
     * through the appropriate cached pipeline chain.
     *
     * @param interfaceType the interface to proxy
     * @param target        the real implementation
     * @param <T>           the interface type
     * @return a proxy that routes calls through the pipeline
     * @throws IllegalArgumentException if interfaceType is not an interface
     * @throws NullPointerException     if any argument is null
     */
    @SuppressWarnings("unchecked")
    public <T> T protect(Class<T> interfaceType, T target) {
        Objects.requireNonNull(interfaceType, "Interface type must not be null");
        Objects.requireNonNull(target, "Target must not be null");

        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException(
                    interfaceType.getName() + " is not an interface. "
                            + "HybridProxyPipelineTerminal uses JDK dynamic proxies, "
                            + "which require an interface type.");
        }

        // Summary string built once at proxy creation; reused for every
        // toString() invocation on the resulting proxy instance.
        String summary = ProxyInvocationSupport.buildSummary(
                "HybridPipelineProxy", interfaceType, target, pipeline);

        return (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[]{interfaceType},
                (proxy, method, args) -> dispatch(proxy, method, args, target, summary));
    }

    // ======================== Generic execution ========================

    /**
     * Executes a sync call through the pipeline (uncached — builds chain per call).
     * Useful for unit tests without a proxy.
     */
    public <R> R execute(JoinPointExecutor<R> executor) throws Throwable {
        @SuppressWarnings("unchecked")
        Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> factory =
                buildSyncChainFactory();
        @SuppressWarnings("unchecked")
        JoinPointExecutor<Object> chain = factory.apply((JoinPointExecutor<Object>) executor);
        @SuppressWarnings("unchecked")
        R result = (R) chain.proceed();
        return result;
    }

    /**
     * Executes an async call through the pipeline (uncached — builds chain per call).
     * Useful for unit tests without a proxy. Never throws.
     */
    @SuppressWarnings("unchecked")
    public <R> CompletionStage<R> executeAsync(
            JoinPointExecutor<CompletionStage<R>> executor) {
        try {
            Function<JoinPointExecutor<CompletionStage<Object>>,
                    JoinPointExecutor<CompletionStage<Object>>> factory =
                    buildAsyncChainFactory();
            JoinPointExecutor<CompletionStage<Object>> chain =
                    factory.apply((JoinPointExecutor<CompletionStage<Object>>) (JoinPointExecutor<?>) executor);
            return (CompletionStage<R>) chain.proceed();
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ======================== Internal: dispatch ========================

    /**
     * Dispatches a proxy method call using the cached chain factory and
     * invoker. The hot path is a single {@code ConcurrentHashMap.get()}
     * followed by a direct invoker call through the pre-composed chain.
     */
    @SuppressWarnings("unchecked")
    private Object dispatch(Object proxy, Method method, Object[] args,
                            Object target, String summary) throws Throwable {

        // Object methods — no pipeline execution, no caching, no invoker resolution.
        // These are infrequent and handled inline.
        if ("toString".equals(method.getName()) && method.getParameterCount() == 0) {
            return summary;
        }
        if ("equals".equals(method.getName()) && method.getParameterCount() == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(method.getName()) && method.getParameterCount() == 0) {
            return System.identityHashCode(proxy);
        }

        // Resolve cached chain + invoker (once per Method)
        CachedChain cached = resolveChain(method);
        MethodInvoker invoker = cached.invoker;

        if (cached.async) {
            // Async: uniform error channel — synchronous failures become
            // failed CompletionStages.
            try {
                JoinPointExecutor<CompletionStage<Object>> terminal =
                        () -> (CompletionStage<Object>) invoker.invoke(target, args);
                return cached.asyncFactory.apply(terminal).proceed();
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        } else {
            // Sync: exceptions propagate directly to the caller.
            JoinPointExecutor<Object> terminal = () -> invoker.invoke(target, args);
            return cached.syncFactory.apply(terminal).proceed();
        }
    }

    // ======================== Internal: caching ========================

    /**
     * Resolves the cached chain entry for the given method, building it
     * on first access. Return-type classification, chain composition, and
     * invoker resolution happen exactly once per Method.
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
     * vs async based on return type, pre-composes the chain factory, and
     * resolves the arity-specialized method invoker.
     */
    private CachedChain buildCachedChain(Method method) {
        MethodInvoker invoker = handleCache.resolveInvoker(method);
        if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            return CachedChain.async(invoker, buildAsyncChainFactory());
        } else {
            return CachedChain.sync(invoker, buildSyncChainFactory());
        }
    }

    // ======================== Internal: chain composition ========================

    /**
     * Pre-composes a sync chain factory by folding the pipeline elements.
     * The result is a function that, given a terminal executor, produces
     * the fully decorated chain in a single apply() call.
     */
    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> buildSyncChainFactory() {
        return pipeline.chain(
                Function.<JoinPointExecutor<Object>>identity(),
                (accFn, element) -> executor ->
                        ((InqDecorator<Void, Object>) asDecorator(element))
                                .decorateJoinPoint(accFn.apply(executor)));
    }

    /**
     * Pre-composes an async chain factory by folding the pipeline elements.
     */
    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>> buildAsyncChainFactory() {
        return pipeline.chain(
                Function.<JoinPointExecutor<CompletionStage<Object>>>identity(),
                (accFn, element) -> executor ->
                        ((InqAsyncDecorator<Void, Object>) asAsyncDecorator(element))
                                .decorateAsyncJoinPoint(accFn.apply(executor)));
    }

    // ======================== Internal: cached chain holder ========================

    /**
     * Holds the pre-composed chain factory and the pre-built
     * {@link MethodInvoker} for a single {@link Method}. Immutable after
     * construction.
     *
     * <p>The sync/async decision is made once and stored in the {@code async}
     * flag. Only the relevant factory field is populated — the other is null.
     * The {@code invoker} field is always populated.</p>
     */
    private static final class CachedChain {

        final boolean async;
        final MethodInvoker invoker;
        final Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> syncFactory;
        final Function<JoinPointExecutor<CompletionStage<Object>>,
                JoinPointExecutor<CompletionStage<Object>>> asyncFactory;

        private CachedChain(
                boolean async,
                MethodInvoker invoker,
                Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> syncFactory,
                Function<JoinPointExecutor<CompletionStage<Object>>,
                        JoinPointExecutor<CompletionStage<Object>>> asyncFactory) {
            this.async = async;
            this.invoker = invoker;
            this.syncFactory = syncFactory;
            this.asyncFactory = asyncFactory;
        }

        static CachedChain sync(MethodInvoker invoker,
                                Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> factory) {
            return new CachedChain(false, invoker, factory, null);
        }

        static CachedChain async(MethodInvoker invoker,
                                 Function<JoinPointExecutor<CompletionStage<Object>>,
                                         JoinPointExecutor<CompletionStage<Object>>> factory) {
            return new CachedChain(true, invoker, null, factory);
        }
    }
}
