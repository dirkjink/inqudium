package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.AsyncJoinPointWrapper;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Abstract base class for AspectJ aspects that execute method calls through
 * an asynchronous wrapper pipeline.
 *
 * <p>The async counterpart to {@link AbstractPipelineAspect}. Subclasses provide
 * the list of {@link AsyncAspectLayerProvider}s; this class assembles and executes
 * the {@link AsyncJoinPointWrapper} chain.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Aspect
 * public class AsyncResilienceAspect extends AbstractAsyncPipelineAspect {
 *
 *     private final List<AsyncAspectLayerProvider<Object>> providers = List.of(
 *         new AsyncBulkheadLayerProvider(),
 *         new AsyncTimingLayerProvider()
 *     );
 *
 *     @Override
 *     protected List<AsyncAspectLayerProvider<Object>> asyncLayerProviders() {
 *         return providers;
 *     }
 *
 *     @Around("@annotation(AsyncResilient)")
 *     public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *         return executeThroughAsync(pjp::proceed);
 *     }
 * }
 * }</pre>
 *
 * <h3>Exception transport</h3>
 * <p>Checked exceptions from the proxied method's synchronous phase are transported
 * via {@link java.util.concurrent.CompletionException} and unwrapped by
 * {@link AsyncJoinPointWrapper#proceed()}. Exceptions during the async completion
 * phase surface through the returned {@link CompletionStage}.</p>
 */
public abstract class AbstractAsyncPipelineAspect {

    /**
     * Returns the ordered list of async layer providers for this aspect.
     *
     * <p>Providers are sorted by {@link AsyncAspectLayerProvider#order()} during
     * chain assembly. Implementations may return a cached, immutable list
     * if the layer configuration is static.</p>
     *
     * @return the async layer providers, never {@code null}
     */
    protected abstract List<AsyncAspectLayerProvider<Object>> asyncLayerProviders();

    /**
     * Builds an async wrapper chain from the configured layer providers and
     * executes the given join point through it.
     *
     * <p>Intended to be called from an {@code @Around} advice method where the
     * proxied method returns a {@link CompletionStage}:</p>
     * <pre>{@code
     * @Around("...")
     * public Object around(ProceedingJoinPoint pjp) throws Throwable {
     *     return executeThroughAsync(pjp::proceed);
     * }
     * }</pre>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed}),
     *                     expected to return a {@link CompletionStage}
     * @return a {@link CompletionStage} carrying the result of the pipeline execution
     * @throws Throwable any synchronous exception from the delegate or from layer
     *                   actions during the start phase, with checked exceptions
     *                   unwrapped from their {@link java.util.concurrent.CompletionException}
     *                   transport
     */
    @SuppressWarnings("unchecked")
    protected CompletionStage<Object> executeThroughAsync(
            JoinPointExecutor<Object> coreExecutor) throws Throwable {

        // The core executor returns a CompletionStage at runtime (the proxied
        // method's return value), but its compile-time type is Object.
        // We bridge this by wrapping the executor in a typed lambda.
        JoinPointExecutor<CompletionStage<Object>> typedExecutor =
                () -> (CompletionStage<Object>) coreExecutor.proceed();

        AsyncJoinPointWrapper<Object> chain = new AsyncAspectPipelineBuilder<Object>()
                .addProviders(asyncLayerProviders())
                .buildChain(typedExecutor);

        return chain.proceed();
    }

    /**
     * Builds the async wrapper chain without executing it.
     *
     * <p>Useful for diagnostics — the returned wrapper exposes
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()} for
     * visualizing the assembled async pipeline structure.</p>
     *
     * @param coreExecutor the async join point execution
     * @return the outermost wrapper of the assembled async chain
     */
    protected AsyncJoinPointWrapper<Object> buildAsyncPipeline(
            JoinPointExecutor<CompletionStage<Object>> coreExecutor) {
        return new AsyncAspectPipelineBuilder<Object>()
                .addProviders(asyncLayerProviders())
                .buildChain(coreExecutor);
    }
}
