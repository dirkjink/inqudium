package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.SyncPipelineTerminal;
import eu.inqudium.imperative.core.pipeline.AsyncPipelineTerminal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Hybrid AspectJ terminal that automatically dispatches sync and async
 * method calls through the appropriate pipeline chain.
 *
 * <p>When the intercepted method returns {@link CompletionStage}, the call
 * is routed through {@link AsyncPipelineTerminal} — elements use
 * {@link eu.inqudium.imperative.core.pipeline.InqAsyncDecorator#decorateAsyncJoinPoint}
 * and the resource lifecycle is tied to the stage's completion. All other
 * methods are routed through {@link SyncPipelineTerminal}.</p>
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
 *         // sync methods  → SyncPipelineTerminal
 *         // async methods → AsyncPipelineTerminal (uniform error channel)
 *     }
 * }
 * }</pre>
 *
 * <h3>Comparison with pure-sync terminals</h3>
 * <table>
 *   <tr><th></th><th>AspectPipelineTerminal</th><th>HybridAspectPipelineTerminal</th></tr>
 *   <tr><td>Sync methods</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>Async methods</td><td>✗ (wrong lifecycle)</td><td>✓ (release on stage complete)</td></tr>
 *   <tr><td>Module</td><td>inqudium-aspect</td><td>inqudium-aspect</td></tr>
 *   <tr><td>Dependencies</td><td>core only</td><td>core + imperative</td></tr>
 * </table>
 *
 * <h3>Element requirements</h3>
 * <p>Pipeline elements must implement both
 * {@link eu.inqudium.core.pipeline.InqDecorator} and
 * {@link eu.inqudium.imperative.core.pipeline.InqAsyncDecorator}.
 * The production elements (Bulkhead, CircuitBreaker, Retry, etc.)
 * implement both interfaces.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use.</p>
 *
 * @since 0.8.0
 */
public final class HybridAspectPipelineTerminal {

    private final InqPipeline pipeline;
    private final SyncPipelineTerminal syncTerminal;
    private final AsyncPipelineTerminal asyncTerminal;

    private HybridAspectPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
        this.syncTerminal = SyncPipelineTerminal.of(pipeline);
        this.asyncTerminal = AsyncPipelineTerminal.of(pipeline);
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
     * automatically choosing the sync or async chain based on the method's
     * return type.
     *
     * <ul>
     *   <li>Return type is {@link CompletionStage} → async chain.
     *       The returned stage carries the result or failure (uniform error
     *       channel — never throws).</li>
     *   <li>All other return types → sync chain.
     *       Exceptions are thrown directly.</li>
     * </ul>
     *
     * <p>This is the one-liner that concrete aspects call from their
     * {@code @Around} method:</p>
     * <pre>{@code
     * @Around("@annotation(Resilient)")
     * public Object around(ProceedingJoinPoint pjp) throws Throwable {
     *     return terminal.executeAround(pjp);
     * }
     * }</pre>
     *
     * @param pjp the proceeding join point provided by AspectJ
     * @return the result — either a direct value (sync) or a
     *         {@link CompletionStage} (async)
     * @throws Throwable any exception from sync methods or pipeline elements
     */
    @SuppressWarnings("unchecked")
    public Object executeAround(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();

        if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            // Async dispatch: pjp.proceed() returns CompletionStage
            return asyncTerminal.execute(() ->
                    (CompletionStage<Object>) pjp.proceed());
        }

        // Sync dispatch
        return syncTerminal.execute(pjp::proceed);
    }

    // ======================== Generic execution ========================

    /**
     * Executes a sync call through the pipeline.
     * Useful for unit tests without AspectJ weaving.
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
     * Useful for unit tests without AspectJ weaving.
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
}
