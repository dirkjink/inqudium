package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.SyncPipelineTerminal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Hybrid dynamic proxy terminal that automatically dispatches sync and
 * async method calls through the appropriate pipeline chain.
 *
 * <p>When a proxied method returns {@link CompletionStage}, the call is
 * routed through {@link AsyncPipelineTerminal} — elements use
 * {@link InqAsyncDecorator#decorateAsyncJoinPoint} and the permit/resource
 * lifecycle is tied to the stage's completion. All other methods are routed
 * through {@link SyncPipelineTerminal}.</p>
 *
 * <h3>Why this matters</h3>
 * <p>A sync-only proxy releases resources (bulkhead permits, circuit breaker
 * accounting) as soon as the method returns. For async methods that return
 * {@code CompletionStage}, the method returns <em>before</em> the actual
 * work completes — sync resource release is premature:</p>
 * <pre>
 *   Sync proxy (wrong for async):     Hybrid proxy (correct):
 *   ────────────────────────           ──────────────────────
 *   BH acquire                        BH acquire
 *   target.callAsync()                target.callAsync()
 *   BH release  ← too early!          → returns CompletionStage
 *   stage still running...             stage.whenComplete → BH release  ← correct
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqPipeline pipeline = InqPipeline.builder()
 *         .shield(circuitBreaker)   // implements both InqDecorator and InqAsyncDecorator
 *         .shield(bulkhead)
 *         .build();
 *
 * HybridProxyPipelineTerminal terminal = HybridProxyPipelineTerminal.of(pipeline);
 * MyService proxy = terminal.protect(MyService.class, realService);
 *
 * // Sync method → SyncPipelineTerminal
 * proxy.findById(42);
 *
 * // Async method → AsyncPipelineTerminal
 * proxy.findByIdAsync(42);  // returns CompletionStage
 * }</pre>
 *
 * <h3>Element requirements</h3>
 * <p>Pipeline elements must implement <strong>both</strong>
 * {@link eu.inqudium.core.pipeline.InqDecorator} (for sync methods) and
 * {@link InqAsyncDecorator} (for async methods). The production elements
 * (Bulkhead, CircuitBreaker, Retry, etc.) implement both interfaces.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances and created proxies are safe for concurrent use.</p>
 *
 * @since 0.8.0
 */
public final class HybridProxyPipelineTerminal {

    private final InqPipeline pipeline;
    private final SyncPipelineTerminal syncTerminal;
    private final AsyncPipelineTerminal asyncTerminal;

    private HybridProxyPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
        this.syncTerminal = SyncPipelineTerminal.of(pipeline);
        this.asyncTerminal = AsyncPipelineTerminal.of(pipeline);
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
     * through the appropriate pipeline chain.
     *
     * <ul>
     *   <li>Methods returning {@link CompletionStage} → async chain
     *       (uniform error channel, never throws)</li>
     *   <li>All other methods → sync chain</li>
     *   <li>{@code toString}, {@code equals}, {@code hashCode} → handled
     *       directly, no pipeline execution</li>
     * </ul>
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
     * Executes a sync call through the pipeline.
     *
     * @param executor the core execution
     * @param <R>      the return type
     * @return the result
     * @throws Throwable any exception from the core or pipeline elements
     */
    public <R> R execute(JoinPointExecutor<R> executor) throws Throwable {
        return syncTerminal.execute(executor);
    }

    /**
     * Executes an async call through the pipeline.
     *
     * <p>Never throws — all errors are delivered via the returned stage.</p>
     *
     * @param executor the core async execution
     * @param <R>      the result type carried by the CompletionStage
     * @return a CompletionStage carrying the result or failure
     */
    public <R> CompletionStage<R> executeAsync(
            JoinPointExecutor<CompletionStage<R>> executor) {
        return asyncTerminal.execute(executor);
    }

    // ======================== Internal ========================

    /**
     * Dispatches a proxy method call to the sync or async chain based on
     * the method's return type.
     */
    @SuppressWarnings("unchecked")
    private Object dispatch(Object proxy, Method method, Object[] args,
                            Object target, String summary) throws Throwable {

        // Object methods — no pipeline execution
        if ("toString".equals(method.getName()) && method.getParameterCount() == 0) {
            return summary;
        }
        if ("equals".equals(method.getName()) && method.getParameterCount() == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(method.getName()) && method.getParameterCount() == 0) {
            return System.identityHashCode(proxy);
        }

        // Async dispatch: return type is CompletionStage
        if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            return asyncTerminal.execute(() ->
                    (CompletionStage<Object>) invokeTarget(method, target, args));
        }

        // Sync dispatch: all other return types
        return syncTerminal.execute(() -> invokeTarget(method, target, args));
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
}
