package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.SyncPipelineTerminal;
import org.aspectj.lang.ProceedingJoinPoint;

import java.util.Objects;

/**
 * AspectJ terminal for an {@link InqPipeline}.
 *
 * <p>Bridges the paradigm-agnostic {@link InqPipeline} with AspectJ's
 * {@link ProceedingJoinPoint}, providing a one-liner for {@code @Around}
 * advice methods. This is the <strong>composition-based</strong> alternative
 * to the inheritance-based {@link AbstractPipelineAspect}:</p>
 *
 * <table>
 *   <tr><th></th><th>AbstractPipelineAspect</th><th>AspectPipelineTerminal</th></tr>
 *   <tr><td>Style</td><td>Inheritance</td><td>Composition</td></tr>
 *   <tr><td>Elements</td><td>AspectLayerProvider (manual order)</td>
 *       <td>InqElement via InqPipeline (PipelineOrdering)</td></tr>
 *   <tr><td>Filtering</td><td>canHandle(Method) per provider</td>
 *       <td>Pre-composed — all elements apply</td></tr>
 *   <tr><td>Ordering</td><td>int order() on each provider</td>
 *       <td>PipelineOrdering (standard, R4J, custom)</td></tr>
 * </table>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Aspect
 * public class ResilienceAspect {
 *
 *     private final AspectPipelineTerminal terminal = AspectPipelineTerminal.of(
 *             InqPipeline.builder()
 *                     .shield(circuitBreaker)
 *                     .shield(retry)
 *                     .shield(bulkhead)
 *                     .build());
 *
 *     @Around("@annotation(Resilient)")
 *     public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *         return terminal.executeAround(pjp);
 *     }
 * }
 * }</pre>
 *
 * <h3>Dispatch agnosticism</h3>
 * <p>While {@link #executeAround} is AspectJ-specific, the {@link #execute}
 * method accepts any {@link JoinPointExecutor} — making the terminal usable
 * in unit tests without AspectJ weaving:</p>
 * <pre>{@code
 * // Unit test — no ProceedingJoinPoint, no weaving
 * AspectPipelineTerminal terminal = AspectPipelineTerminal.of(pipeline);
 * Object result = terminal.execute(() -> "test-value");
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The underlying
 * {@link InqPipeline} and {@link SyncPipelineTerminal} are both immutable.</p>
 *
 * @since 0.8.0
 */
public final class AspectPipelineTerminal {

    private final InqPipeline pipeline;
    private final SyncPipelineTerminal syncTerminal;

    private AspectPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
        this.syncTerminal = SyncPipelineTerminal.of(pipeline);
    }

    /**
     * Creates an aspect terminal for the given pipeline.
     *
     * @param pipeline the composed pipeline
     * @return the aspect terminal
     * @throws NullPointerException if pipeline is null
     */
    public static AspectPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new AspectPipelineTerminal(pipeline);
    }

    /**
     * Returns the underlying pipeline.
     *
     * @return the pipeline
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    // ======================== AspectJ execution ========================

    /**
     * Executes the given {@link ProceedingJoinPoint} through the pipeline.
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
     * <p>Internally, {@code pjp::proceed} is passed as a
     * {@link JoinPointExecutor} to the sync pipeline terminal, which
     * folds the pipeline elements via
     * {@link eu.inqudium.core.pipeline.InqDecorator#decorateJoinPoint}.</p>
     *
     * @param pjp the proceeding join point provided by AspectJ
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the target method or pipeline elements
     */
    public Object executeAround(ProceedingJoinPoint pjp) throws Throwable {
        return syncTerminal.execute(pjp::proceed);
    }

    // ======================== Generic execution ========================

    /**
     * Executes the given {@link JoinPointExecutor} through the pipeline.
     *
     * <p>Dispatch-agnostic — works with any executor:</p>
     * <ul>
     *   <li>Plain lambda: {@code () -> service.call()}</li>
     *   <li>AspectJ join point: {@code pjp::proceed}</li>
     *   <li>JDK Proxy target: {@code () -> method.invoke(target, args)}</li>
     * </ul>
     *
     * <p>Useful for unit tests without AspectJ weaving.</p>
     *
     * @param executor the core execution
     * @param <R>      the return type
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the core or pipeline elements
     */
    public <R> R execute(JoinPointExecutor<R> executor) throws Throwable {
        return syncTerminal.execute(executor);
    }

    // ======================== Decoration ========================

    /**
     * Builds a decorator chain around the given executor without executing it.
     *
     * <p>Useful for deferred execution or when the caller needs to control
     * when the pipeline runs.</p>
     *
     * @param executor the core execution
     * @param <R>      the return type
     * @return the decorated executor
     */
    public <R> JoinPointExecutor<R> decorateJoinPoint(JoinPointExecutor<R> executor) {
        return syncTerminal.decorateJoinPoint(executor);
    }
}
