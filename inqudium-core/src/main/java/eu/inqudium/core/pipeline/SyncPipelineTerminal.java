package eu.inqudium.core.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Synchronous terminal for an {@link InqPipeline}.
 *
 * <p>Takes a paradigm-agnostic pipeline and provides sync execution methods
 * by folding the elements via {@link InqDecorator#decorateJoinPoint}.
 * Each element in the pipeline must implement {@link InqDecorator} — if an
 * element does not, a descriptive {@link ClassCastException} is thrown at
 * chain-build time.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqPipeline pipeline = InqPipeline.builder()
 *         .shield(circuitBreaker)
 *         .shield(retry)
 *         .build();
 *
 * SyncPipelineTerminal terminal = SyncPipelineTerminal.of(pipeline);
 *
 * // Execute immediately
 * String result = terminal.execute(() -> service.call());
 *
 * // Decorate for repeated execution
 * Supplier<String> decorated = terminal.decorateSupplier(() -> service.call());
 * decorated.get();  // each call goes through the pipeline
 *
 * // Decorate a JoinPointExecutor (for proxy / AspectJ integration)
 * JoinPointExecutor<String> chain = terminal.decorateJoinPoint(() -> service.call());
 * chain.proceed();
 * }</pre>
 *
 * <h3>Dispatch mechanism integration</h3>
 * <p>{@code SyncPipelineTerminal} is dispatch-agnostic — the caller provides
 * the core executor, which can be:</p>
 * <ul>
 *   <li>A plain lambda: {@code () -> service.call()}</li>
 *   <li>A JDK Proxy target: {@code () -> method.invoke(target, args)}</li>
 *   <li>An AspectJ join point: {@code pjp::proceed}</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The decorated
 * chain is built on each call to {@code decorateJoinPoint()} — use
 * caching (e.g. {@code ResolvedPipeline}) for hot-path optimization.</p>
 *
 * @since 0.8.0
 */
public final class SyncPipelineTerminal {

    private final InqPipeline pipeline;

    private SyncPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Creates a sync terminal for the given pipeline.
     *
     * @param pipeline the composed pipeline
     * @return the sync terminal
     * @throws NullPointerException if pipeline is null
     */
    public static SyncPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new SyncPipelineTerminal(pipeline);
    }

    /**
     * Returns the underlying pipeline.
     *
     * @return the pipeline
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    // ======================== Execution ========================

    /**
     * Builds the decorator chain and executes it immediately.
     *
     * <p>Equivalent to {@code decorateJoinPoint(executor).proceed()}.</p>
     *
     * @param executor the core execution (e.g. {@code () -> service.call()},
     *                 {@code pjp::proceed}, or {@code () -> method.invoke(target, args)})
     * @param <R>      the return type
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the core or from pipeline elements
     */
    public <R> R execute(JoinPointExecutor<R> executor) throws Throwable {
        return decorateJoinPoint(executor).proceed();
    }

    // ======================== Decoration ========================

    /**
     * Builds a decorator chain around the given {@link JoinPointExecutor}.
     *
     * <p>Each element in the pipeline is folded via
     * {@link InqDecorator#decorateJoinPoint}, producing a nested chain
     * where the outermost element intercepts first:</p>
     * <pre>
     *   outermost.decorateJoinPoint(
     *       middle.decorateJoinPoint(
     *           innermost.decorateJoinPoint(executor)))
     * </pre>
     *
     * @param executor the core execution at the bottom of the chain
     * @param <R>      the return type
     * @return the decorated executor
     * @throws ClassCastException if any element does not implement {@link InqDecorator}
     */
    @SuppressWarnings("unchecked")
    public <R> JoinPointExecutor<R> decorateJoinPoint(JoinPointExecutor<R> executor) {
        return pipeline.chain(executor, (downstream, element) ->
                asDecorator(element).decorateJoinPoint(downstream));
    }

    /**
     * Builds a decorator chain and wraps it as a {@link Supplier}.
     *
     * <p>Checked exceptions from the pipeline are wrapped in
     * {@link RuntimeException}. If the core execution or any element throws
     * only unchecked exceptions, no wrapping occurs.</p>
     *
     * @param supplier the core supplier
     * @param <R>      the return type
     * @return a decorated supplier
     */
    public <R> Supplier<R> decorateSupplier(Supplier<R> supplier) {
        JoinPointExecutor<R> chain = decorateJoinPoint(supplier::get);
        return () -> {
            try {
                return chain.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        };
    }

    // ======================== Internal ========================

    /**
     * Casts an {@link InqElement} to {@link InqDecorator}, providing a
     * descriptive error if the element does not implement the sync decorator.
     */
    private static InqDecorator<?, ?> asDecorator(InqElement element) {
        if (element instanceof InqDecorator<?, ?> decorator) {
            return decorator;
        }
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqDecorator. "
                        + "SyncPipelineTerminal requires all pipeline elements to "
                        + "implement InqDecorator<A, R>. For async elements, use "
                        + "AsyncPipelineTerminal instead.");
    }
}
