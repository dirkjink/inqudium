package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.SyncPipelineTerminal;

import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Dynamic proxy terminal for an {@link InqPipeline}.
 *
 * <p>Creates JDK dynamic proxies that route every interface method call
 * through the pipeline. This is the <strong>composition-based</strong>
 * alternative to manually creating an {@code InqProxyFactory} with a
 * single {@code LayerAction}:</p>
 *
 * <table>
 *   <tr><th></th><th>InqProxyFactory</th><th>ProxyPipelineTerminal</th></tr>
 *   <tr><td>Style</td><td>Single LayerAction</td><td>InqPipeline with N elements</td></tr>
 *   <tr><td>Ordering</td><td>Manual nesting</td><td>PipelineOrdering (standard, R4J, custom)</td></tr>
 *   <tr><td>Elements</td><td>One action per factory</td>
 *       <td>N InqDecorators, auto-sorted</td></tr>
 * </table>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqPipeline pipeline = InqPipeline.builder()
 *         .shield(circuitBreaker)
 *         .shield(bulkhead)
 *         .shield(retry)
 *         .build();
 *
 * ProxyPipelineTerminal terminal = ProxyPipelineTerminal.of(pipeline);
 *
 * // Create a protected proxy — all method calls go through the pipeline
 * MyService proxy = terminal.protect(MyService.class, realService);
 * proxy.doWork();  // → BH → CB → RT → realService.doWork()
 *
 * // Same pipeline, different targets
 * OtherService otherProxy = terminal.protect(OtherService.class, realOther);
 * }</pre>
 *
 * <h3>Method dispatch</h3>
 * <ul>
 *   <li>Interface methods: routed through the pipeline via
 *       {@link SyncPipelineTerminal#execute}, invoked on the target through
 *       a pre-built {@link MethodInvoker} — no reflection overhead, no
 *       {@code InvocationTargetException} wrap/unwrap on the hot path.</li>
 *   <li>{@code toString()}: returns a diagnostic string showing the pipeline
 *       structure and target class (see
 *       {@link ProxyInvocationSupport#buildSummary}).</li>
 *   <li>{@code equals()}, {@code hashCode()}: delegated to the proxy identity
 *       (not the target) to avoid accidental pipeline execution.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. Created proxies
 * are also thread-safe — the pipeline elements determine concurrency
 * behavior (e.g. a bulkhead limits concurrent calls). The per-instance
 * {@link MethodHandleCache} is thread-safe and populated lazily per method.</p>
 *
 * @since 0.8.0
 */
public final class ProxyPipelineTerminal {

    private final InqPipeline pipeline;
    private final SyncPipelineTerminal syncTerminal;

    /**
     * Per-instance cache of {@link MethodInvoker}s. Each proxied method
     * has its invoker resolved here exactly once, then reused across all
     * subsequent invocations.
     */
    private final MethodHandleCache handleCache = new MethodHandleCache();

    private ProxyPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
        this.syncTerminal = SyncPipelineTerminal.of(pipeline);
    }

    /**
     * Creates a proxy terminal for the given pipeline.
     *
     * @param pipeline the composed pipeline
     * @return the proxy terminal
     * @throws NullPointerException if pipeline is null
     */
    public static ProxyPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new ProxyPipelineTerminal(pipeline);
    }

    // ======================== Proxy creation ========================

    /**
     * Returns the underlying pipeline.
     *
     * @return the pipeline
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    /**
     * Creates a JDK dynamic proxy that routes all method calls through
     * the pipeline before delegating to the target.
     *
     * <p>The proxy implements the given interface. Every method call on
     * the proxy traverses the full pipeline (outermost element first,
     * innermost last), then invokes the corresponding method on the
     * target through a pre-built {@link MethodInvoker}.</p>
     *
     * <pre>{@code
     * MyService proxy = terminal.protect(MyService.class, realService);
     *
     * // This call goes through: BH → CB → RT → realService.doWork()
     * proxy.doWork();
     * }</pre>
     *
     * @param interfaceType the interface to proxy (must be an interface)
     * @param target        the real implementation to delegate to
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
                            + "ProxyPipelineTerminal uses JDK dynamic proxies, "
                            + "which require an interface type.");
        }

        // Summary string built once at proxy creation; reused for every
        // toString() invocation on the resulting proxy instance.
        String pipelineSummary = ProxyInvocationSupport.buildSummary(
                "InqPipelineProxy", interfaceType, target, pipeline);

        return (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[]{interfaceType},
                (proxy, method, args) -> {

                    // Object.toString — diagnostic output
                    if ("toString".equals(method.getName())
                            && method.getParameterCount() == 0) {
                        return pipelineSummary;
                    }

                    // Object.equals — proxy identity
                    if ("equals".equals(method.getName())
                            && method.getParameterCount() == 1) {
                        return proxy == args[0];
                    }

                    // Object.hashCode — proxy identity
                    if ("hashCode".equals(method.getName())
                            && method.getParameterCount() == 0) {
                        return System.identityHashCode(proxy);
                    }

                    // Service methods — resolve the pre-built invoker once per
                    // method (cached on the second invocation), then route the
                    // terminal through the sync pipeline. The MethodInvoker path
                    // replaces the previous Method.invoke reflection call and
                    // removes the InvocationTargetException wrap/unwrap overhead.
                    MethodInvoker invoker = handleCache.resolveInvoker(method);
                    return syncTerminal.execute(() -> invoker.invoke(target, args));
                });
    }
}
