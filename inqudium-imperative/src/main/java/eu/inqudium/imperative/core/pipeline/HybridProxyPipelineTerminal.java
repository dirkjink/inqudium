package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;

import java.lang.reflect.InvocationTargetException;
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
 * <p>The sync/async decision and the decorator chain factory are resolved
 * <strong>once per {@link Method}</strong> and cached in a
 * {@link ConcurrentHashMap}. Subsequent invocations of the same method
 * only create the terminal lambda ({@code () → method.invoke(target, args)})
 * and apply the pre-composed chain factory — no iteration, no sorting,
 * no {@code isAssignableFrom} check.</p>
 *
 * <pre>
 *   First call to placeOrder():
 *     1. method.getReturnType() → String → sync         ← once
 *     2. pipeline.chain(identity, fold) → chainFactory   ← once
 *     3. cache.put(placeOrder, CachedChain.sync(factory))
 *     4. factory.apply(() → method.invoke(target, args)) ← per call
 *     5. chain.proceed()                                  ← per call
 *
 *   Subsequent calls to placeOrder():
 *     1. cache.get(placeOrder) → CachedChain.sync(factory)  ← fast path
 *     2. factory.apply(() → method.invoke(target, args))     ← per call
 *     3. chain.proceed()                                      ← per call
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
 * to the same method may redundantly build the chain factory (benign race),
 * but the result is always identical and immutable.</p>
 *
 * @since 0.8.0
 */
public final class HybridProxyPipelineTerminal {

    private final InqPipeline pipeline;

    /**
     * Pre-composed chain factories, cached per Method. The fast path
     * (after first access) is a single {@code ConcurrentHashMap.get()}.
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

    /**
     * Returns the underlying pipeline.
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    // ======================== Proxy creation ========================

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

        String summary = buildSummary(interfaceType, target);

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
     * Dispatches a proxy method call using the cached chain factory.
     */
    @SuppressWarnings("unchecked")
    private Object dispatch(Object proxy, Method method, Object[] args,
                            Object target, String summary) throws Throwable {

        // Object methods — no pipeline execution, no caching
        if ("toString".equals(method.getName()) && method.getParameterCount() == 0) {
            return summary;
        }
        if ("equals".equals(method.getName()) && method.getParameterCount() == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(method.getName()) && method.getParameterCount() == 0) {
            return System.identityHashCode(proxy);
        }

        // Resolve cached chain factory (once per Method)
        CachedChain cached = resolveChain(method);

        if (cached.async) {
            // Async: apply pre-composed async factory to the terminal lambda
            try {
                JoinPointExecutor<CompletionStage<Object>> terminal =
                        () -> (CompletionStage<Object>) invokeTarget(method, target, args);
                return cached.asyncFactory.apply(terminal).proceed();
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        } else {
            // Sync: apply pre-composed sync factory to the terminal lambda
            JoinPointExecutor<Object> terminal = () -> invokeTarget(method, target, args);
            return cached.syncFactory.apply(terminal).proceed();
        }
    }

    // ======================== Internal: caching ========================

    /**
     * Resolves the cached chain factory for the given method, building it
     * on first access. The return-type check and chain composition happen
     * exactly once per Method.
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

    // ======================== Internal: casting ========================

    private static InqDecorator<?, ?> asDecorator(InqElement element) {
        if (element instanceof InqDecorator<?, ?> d) return d;
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqDecorator. "
                        + "HybridProxyPipelineTerminal requires all elements to implement "
                        + "InqDecorator for sync methods.");
    }

    private static InqAsyncDecorator<?, ?> asAsyncDecorator(InqElement element) {
        if (element instanceof InqAsyncDecorator<?, ?> d) return d;
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqAsyncDecorator. "
                        + "HybridProxyPipelineTerminal requires all elements to implement "
                        + "InqAsyncDecorator for async methods (returning CompletionStage).");
    }

    private static Object invokeTarget(Method method, Object target, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private String buildSummary(Class<?> interfaceType, Object target) {
        StringBuilder sb = new StringBuilder();
        sb.append("HybridPipelineProxy[")
                .append(interfaceType.getSimpleName())
                .append(" → ")
                .append(target.getClass().getSimpleName())
                .append(", ");
        if (pipeline.isEmpty()) {
            sb.append("no elements (pass-through)");
        } else {
            sb.append(pipeline.depth()).append(" elements: ");
            sb.append(pipeline.chain("target",
                    (acc, element) -> element.getName() + " → " + acc));
        }
        sb.append(']');
        return sb.toString();
    }

    // ======================== Internal: cached chain holder ========================

    /**
     * Holds the pre-composed chain factory for a single {@link Method}.
     * Immutable after construction.
     *
     * <p>The sync/async decision is made once and stored in the {@code async}
     * flag. Only the relevant factory field is populated — the other is null.</p>
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
