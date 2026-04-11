package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Abstract base class for AspectJ aspects that execute method calls through a
 * wrapper pipeline.
 *
 * <p>Subclasses provide the list of {@link AspectLayerProvider}s; this class
 * assembles and executes the {@link JoinPointWrapper} chain. The typical usage
 * pattern is:</p>
 *
 * <pre>{@code
 * @Aspect
 * public class ResilienceAspect extends AbstractPipelineAspect {
 *
 *     private final List<AspectLayerProvider<Object>> providers = List.of(
 *         new LoggingLayerProvider(),
 *         new TimingLayerProvider(),
 *         new RetryLayerProvider()
 *     );
 *
 *     @Override
 *     protected List<AspectLayerProvider<Object>> layerProviders() {
 *         return providers;
 *     }
 *
 *     @Around("@annotation(Resilient)")
 *     public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *         return executeThrough(pjp::proceed);
 *     }
 * }
 * }</pre>
 *
 * <h3>Chain lifecycle</h3>
 * <p>A fresh {@link JoinPointWrapper} chain is built for every advice invocation.
 * This ensures that per-invocation state (call IDs, per-call metrics) is isolated,
 * while the immutable layer definitions from the providers are safely shared.</p>
 *
 * <h3>Exception transport</h3>
 * <p>Checked exceptions from the proxied method are transported through the chain
 * via {@link java.util.concurrent.CompletionException} and unwrapped by
 * {@link JoinPointWrapper#proceed()}. The caller sees the original throwable type,
 * preserving the proxied method's exception contract.</p>
 */
public abstract class AbstractPipelineAspect {

    /**
     * Returns the ordered list of layer providers for this aspect.
     *
     * <p>Providers are sorted by {@link AspectLayerProvider#order()} during
     * chain assembly. Implementations may return a cached, immutable list
     * if the layer configuration is static.</p>
     *
     * @return the layer providers to assemble into the pipeline, never {@code null}
     */
    protected abstract List<AspectLayerProvider<Object>> layerProviders();

    /**
     * Builds a wrapper chain from the configured layer providers and executes
     * the given join point through it.
     *
     * <p>Intended to be called from an {@code @Around} advice method:</p>
     * <pre>{@code
     * @Around("...")
     * public Object around(ProceedingJoinPoint pjp) throws Throwable {
     *     return executeThrough(pjp::proceed);
     * }
     * }</pre>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed})
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions,
     *                   with checked exceptions unwrapped from their
     *                   {@link java.util.concurrent.CompletionException} transport
     */
    protected Object executeThrough(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        JoinPointWrapper<Object> chain = new AspectPipelineBuilder<Object>()
                .addProviders(layerProviders())
                .buildChain(coreExecutor);

        return chain.proceed();
    }

    /**
     * Builds the wrapper chain without executing it.
     *
     * <p>Useful for diagnostics — the returned wrapper exposes
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()} for
     * visualizing the assembled pipeline structure.</p>
     *
     * @param coreExecutor the join point execution
     * @return the outermost wrapper of the assembled chain
     */
    protected JoinPointWrapper<Object> buildPipeline(JoinPointExecutor<Object> coreExecutor) {
        return new AspectPipelineBuilder<Object>()
                .addProviders(layerProviders())
                .buildChain(coreExecutor);
    }

    /**
     * Builds a wrapper chain filtered by the target method and executes the
     * given join point through it.
     *
     * <p>Only providers whose {@link AspectLayerProvider#canHandle(Method)} returns
     * {@code true} for the given method are included in the pipeline. This enables
     * method-specific layer composition.</p>
     *
     * <pre>{@code
     * @Around("...")
     * public Object around(ProceedingJoinPoint pjp) throws Throwable {
     *     Method method = ((MethodSignature) pjp.getSignature()).getMethod();
     *     return executeThrough(pjp::proceed, method);
     * }
     * }</pre>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed})
     * @param method       the target method, used to filter providers via {@code canHandle}
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    protected Object executeThrough(JoinPointExecutor<Object> coreExecutor,
                                    Method method) throws Throwable {
        JoinPointWrapper<Object> chain = new AspectPipelineBuilder<Object>()
                .addProviders(layerProviders(), method)
                .buildChain(coreExecutor);

        return chain.proceed();
    }

    /**
     * Builds the wrapper chain filtered by the target method, without executing it.
     *
     * @param coreExecutor the join point execution
     * @param method       the target method, used to filter providers via {@code canHandle}
     * @return the outermost wrapper of the assembled chain
     */
    protected JoinPointWrapper<Object> buildPipeline(JoinPointExecutor<Object> coreExecutor,
                                                     Method method) {
        return new AspectPipelineBuilder<Object>()
                .addProviders(layerProviders(), method)
                .buildChain(coreExecutor);
    }
}
