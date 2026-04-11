package eu.inqudium.aspect.pipeline.example;

import eu.inqudium.aspect.pipeline.AbstractPipelineAspect;
import eu.inqudium.aspect.pipeline.AspectLayerProvider;
import eu.inqudium.aspect.pipeline.ResolvedPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Concrete aspect that weaves a three-layer pipeline around every method
 * annotated with {@link Pipelined}.
 *
 * <h3>Pipeline structure (outermost → innermost)</h3>
 * <ol>
 *   <li><strong>AUTHORIZATION</strong> (order 10) — checks access rights;
 *       short-circuits the chain if denied.</li>
 *   <li><strong>LOGGING</strong> (order 20) — records entry, exit, and
 *       exception events with chain/call IDs.</li>
 *   <li><strong>TIMING</strong> (order 30) — measures the execution
 *       duration of the core method.</li>
 * </ol>
 *
 * <h3>How AspectJ weaving works</h3>
 * <p>At compile-time (or load-time with the weaver agent), AspectJ identifies
 * all methods annotated with {@code @Pipelined}. For each such method, the
 * compiler replaces the direct call with an invocation that first enters
 * the {@link #around(ProceedingJoinPoint)} advice. The advice receives a
 * {@link ProceedingJoinPoint} — a handle to the original method. Calling
 * {@code pjp.proceed()} invokes the real method.</p>
 *
 * <p>This aspect passes {@code pjp::proceed} as a {@link JoinPointExecutor}
 * to {@link #executeThrough(JoinPointExecutor)}, which builds a
 * {@link JoinPointWrapper} chain and executes the method through all three
 * layers.</p>
 *
 * <h3>Example call flow for {@code greetingService.greet("World")}</h3>
 * <pre>
 *   caller
 *     → AspectJ advice (around)
 *       → AUTHORIZATION layer  (check access)
 *         → LOGGING layer      (log entry)
 *           → TIMING layer     (start timer)
 *             → greet("World") (actual method)
 *           ← TIMING layer     (stop timer)
 *         ← LOGGING layer      (log exit)
 *       ← AUTHORIZATION layer
 *     ← AspectJ advice
 *   ← caller receives "Hello, World!"
 * </pre>
 */
@Aspect
public class PipelinedAspect extends AbstractPipelineAspect {

    private final List<AspectLayerProvider<Object>> providers;

    /**
     * Creates the aspect with injectable layer providers.
     *
     * <p>Accepting providers via the constructor makes the aspect testable:
     * tests can supply providers with trace lists, mocked authorization,
     * or custom behavior.</p>
     *
     * @param providers the ordered layer providers for the pipeline
     */
    public PipelinedAspect(List<AspectLayerProvider<Object>> providers) {
        this.providers = providers;
    }

    @Override
    protected List<AspectLayerProvider<Object>> layerProviders() {
        return providers;
    }

    /**
     * Around-advice that intercepts all {@code @Pipelined} methods.
     *
     * <p>AspectJ calls this method instead of the original. The original
     * method is accessible via {@code pjp.proceed()}, which is passed
     * as a method reference ({@code pjp::proceed}) to the pipeline.</p>
     *
     * @param pjp the proceeding join point — handle to the intercepted method
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the method or the pipeline layers
     */
    @Around("@annotation(eu.inqudium.aspect.pipeline.example.Pipelined)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        return execute(pjp::proceed, method);
    }

    /**
     * Public entry point for executing a {@link JoinPointExecutor} through
     * the full pipeline, including all providers regardless of method.
     *
     * <p>Delegates to the {@code protected} {@link #executeThrough(JoinPointExecutor)}
     * from the base class. Useful when no {@link Method} context is available.</p>
     *
     * @param coreExecutor the core execution point
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the method or the pipeline layers
     */
    public Object execute(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        return executeThrough(coreExecutor);
    }

    /**
     * Public entry point for executing a {@link JoinPointExecutor} through
     * the pipeline, filtered by the target method.
     *
     * <p>Only providers whose {@link AspectLayerProvider#canHandle(Method)} returns
     * {@code true} for the given method are included in the chain.</p>
     *
     * @param coreExecutor the core execution point (e.g. {@code pjp::proceed}
     *                     or a lambda wrapping a service method)
     * @param method       the target method, used to filter providers
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the method or the pipeline layers
     */
    public Object execute(JoinPointExecutor<Object> coreExecutor, Method method)
            throws Throwable {
        return executeThrough(coreExecutor, method);
    }

    /**
     * Exposes the pipeline structure for a given core executor without
     * executing it, including all providers regardless of method.
     *
     * @param coreExecutor the core execution point
     * @return the outermost wrapper of the assembled chain
     */
    public JoinPointWrapper<Object> inspectPipeline(JoinPointExecutor<Object> coreExecutor) {
        return buildPipeline(coreExecutor);
    }

    /**
     * Exposes the pipeline structure filtered by the target method, without
     * executing it.
     *
     * <p>Useful for diagnostic and testing purposes — the returned
     * {@link JoinPointWrapper} can be introspected via the
     * {@link eu.inqudium.core.pipeline.Wrapper} interface to verify which
     * layers are active for a specific method.</p>
     *
     * @param coreExecutor the core execution point
     * @param method       the target method, used to filter providers
     * @return the outermost wrapper of the assembled chain
     */
    public JoinPointWrapper<Object> inspectPipeline(JoinPointExecutor<Object> coreExecutor,
                                                    Method method) {
        return buildPipeline(coreExecutor, method);
    }

    /**
     * Returns the cached {@link ResolvedPipeline} for the given method.
     *
     * <p>The pipeline is resolved on first access and reused on subsequent calls.
     * Useful for verifying cached pipeline structure in tests and for
     * diagnostics.</p>
     *
     * @param method the target method
     * @return the pre-composed, cached pipeline
     */
    public ResolvedPipeline getResolvedPipeline(Method method) {
        return resolvedPipeline(method);
    }
}
