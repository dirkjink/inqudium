package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Asynchronous terminal for an {@link InqPipeline}.
 *
 * <p>Takes a paradigm-agnostic pipeline and provides async execution methods
 * by folding the elements via {@link InqAsyncDecorator#decorateAsyncJoinPoint}.
 * Each element in the pipeline must implement {@link InqAsyncDecorator} — if
 * an element does not, a descriptive {@link ClassCastException} is thrown
 * eagerly from {@link #of(InqPipeline)}.</p>
 *
 * <h3>Hot-path optimisation: pre-composed chain factory</h3>
 * <p>The pipeline fold — including all {@link InqAsyncDecorator} casts and the
 * nested {@code decorateAsyncJoinPoint} composition — happens <strong>once</strong>
 * in the constructor and is stored as a single {@link Function}. Subsequent
 * {@link #execute} and {@link #decorateJoinPoint} calls apply this pre-composed
 * factory to the caller-supplied terminal executor. This eliminates {@code N}
 * per-call type-checks and fold steps, where {@code N} is the number of pipeline
 * elements.</p>
 *
 * <h3>Eager validation</h3>
 * <p>Because the fold runs at construction time, elements that fail to implement
 * {@link InqAsyncDecorator} are reported immediately from {@link #of} — not from
 * the first {@code execute()} call. This is a behavioural change from the
 * pre-0.8.0 implementation: callers relying on "late validation" will now see
 * the {@link ClassCastException} earlier. This mirrors the behaviour already
 * adopted by {@code SyncPipelineTerminal}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqPipeline pipeline = InqPipeline.builder()
 *         .shield(circuitBreaker)
 *         .shield(retry)
 *         .build();
 *
 * AsyncPipelineTerminal terminal = AsyncPipelineTerminal.of(pipeline);
 *
 * // Execute immediately — returns CompletionStage, never throws
 * CompletionStage<String> result = terminal.execute(() ->
 *         CompletableFuture.supplyAsync(() -> service.call()));
 *
 * // Decorate for repeated execution
 * Supplier<CompletionStage<String>> decorated = terminal.decorateSupplier(() ->
 *         CompletableFuture.supplyAsync(() -> service.call()));
 * decorated.get().thenAccept(System.out::println);
 *
 * // Decorate a JoinPointExecutor (for proxy / AspectJ integration)
 * JoinPointExecutor<CompletionStage<String>> chain = terminal.decorateJoinPoint(
 *         () -> CompletableFuture.supplyAsync(() -> service.call()));
 * chain.proceed().thenAccept(System.out::println);
 * }</pre>
 *
 * <h3>Uniform error channel</h3>
 * <p>The {@link #execute} method <strong>never throws</strong>. All exceptions
 * — whether from the synchronous start phase (e.g. a layer throwing before
 * creating a {@code CompletionStage}) or from the asynchronous completion
 * phase — are delivered through the returned {@code CompletionStage}. This
 * eliminates a common pitfall where callers must handle two error paths.</p>
 *
 * <h3>Dispatch mechanism integration</h3>
 * <p>{@code AsyncPipelineTerminal} is dispatch-agnostic — the caller provides
 * the core executor, which can be:</p>
 * <ul>
 *   <li>A plain lambda: {@code () -> CompletableFuture.supplyAsync(...)}</li>
 *   <li>A JDK Proxy target: {@code () -> (CompletionStage) method.invoke(target, args)}</li>
 *   <li>An AspectJ join point: {@code () -> (CompletionStage) pjp.proceed()}</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The pre-composed
 * chain factory is constructed once and shared across threads.</p>
 *
 * @since 0.8.0
 */
public final class AsyncPipelineTerminal {

    private final InqPipeline pipeline;

    /**
     * Pre-composed chain factory — captures the entire pipeline fold,
     * including all {@link InqAsyncDecorator} casts. Applied per call to
     * the caller-supplied terminal executor.
     */
    private final Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>> chainFactory;

    private AsyncPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
        this.chainFactory = buildChainFactory(pipeline);
    }

    /**
     * Creates an async terminal for the given pipeline.
     *
     * <p>The pipeline's chain factory is built eagerly — any element that
     * does not implement {@link InqAsyncDecorator} causes an immediate
     * {@link ClassCastException} with a descriptive message.</p>
     *
     * @param pipeline the composed pipeline
     * @return the async terminal
     * @throws NullPointerException if pipeline is null
     * @throws ClassCastException   if any element is not an {@link InqAsyncDecorator}
     */
    public static AsyncPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new AsyncPipelineTerminal(pipeline);
    }

    /**
     * Builds the per-instance chain factory by folding the pipeline elements
     * once. Each element contributes a function that composes its
     * {@code decorateAsyncJoinPoint} over the downstream accumulator.
     *
     * <p>The resulting factory, when applied to a terminal executor, produces
     * the fully decorated chain — outermost element first, innermost last —
     * with no per-call type checks.</p>
     */
    @SuppressWarnings("unchecked")
    private static Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>> buildChainFactory(InqPipeline pipeline) {
        return pipeline.chain(
                Function.<JoinPointExecutor<CompletionStage<Object>>>identity(),
                (accFn, element) -> executor ->
                        ((InqAsyncDecorator<Void, Object>) asAsyncDecorator(element))
                                .decorateAsyncJoinPoint(accFn.apply(executor)));
    }

    /**
     * Casts an {@link InqElement} to {@link InqAsyncDecorator}, providing a
     * descriptive error if the element does not implement the async decorator.
     */
    private static InqAsyncDecorator<?, ?> asAsyncDecorator(InqElement element) {
        if (element instanceof InqAsyncDecorator<?, ?> decorator) {
            return decorator;
        }
        throw new ClassCastException(
                element.getClass().getName() + " ('" + element.getName()
                        + "', type=" + element.getElementType()
                        + ") does not implement InqAsyncDecorator. "
                        + "AsyncPipelineTerminal requires all pipeline elements to "
                        + "implement InqAsyncDecorator<A, R>. For sync elements, use "
                        + "SyncPipelineTerminal instead.");
    }

    // ======================== Execution ========================

    /**
     * Returns the underlying pipeline.
     *
     * @return the pipeline
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    /**
     * Builds the async decorator chain and executes it immediately.
     *
     * <p>This method <strong>never throws</strong>. All exceptions — including
     * synchronous failures from a layer's start phase — are delivered through
     * the returned {@link CompletionStage}. Callers can handle all errors
     * uniformly via {@code .exceptionally()}, {@code .handle()}, or
     * {@code .whenComplete()}.</p>
     *
     * @param executor the core async execution (e.g.
     *                 {@code () -> CompletableFuture.supplyAsync(() -> service.call())})
     * @param <R>      the result type carried by the CompletionStage
     * @return a CompletionStage carrying the result or the failure — never {@code null}
     */
    public <R> CompletionStage<R> execute(
            JoinPointExecutor<CompletionStage<R>> executor) {
        try {
            return decorateJoinPoint(executor).proceed();
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ======================== Decoration ========================

    /**
     * Builds an async decorator chain around the given {@link JoinPointExecutor}.
     *
     * <p>Applies the pre-composed {@link #chainFactory} to the caller-supplied
     * terminal — no per-call pipeline fold, no per-call type checks.</p>
     *
     * @param executor the core async execution at the bottom of the chain
     * @param <R>      the result type carried by the CompletionStage
     * @return the decorated async executor
     */
    @SuppressWarnings("unchecked")
    public <R> JoinPointExecutor<CompletionStage<R>> decorateJoinPoint(
            JoinPointExecutor<CompletionStage<R>> executor) {
        // Generic erasure: at runtime every JoinPointExecutor<CompletionStage<X>>
        // is structurally the same. The chainFactory is built over Object, so
        // we cast to and from that representation.
        return (JoinPointExecutor<CompletionStage<R>>) (JoinPointExecutor<?>)
                chainFactory.apply(
                        (JoinPointExecutor<CompletionStage<Object>>) (JoinPointExecutor<?>) executor);
    }

    /**
     * Builds an async decorator chain and wraps it as a {@link Supplier}.
     *
     * <p>The returned supplier <strong>never throws</strong> — synchronous
     * exceptions are wrapped in a failed {@link CompletionStage}, consistent
     * with the uniform error channel.</p>
     *
     * @param supplier the core async supplier
     * @param <R>      the result type carried by the CompletionStage
     * @return a decorated supplier that returns {@code CompletionStage<R>}
     */
    public <R> Supplier<CompletionStage<R>> decorateSupplier(
            Supplier<CompletionStage<R>> supplier) {
        JoinPointExecutor<CompletionStage<R>> chain = decorateJoinPoint(supplier::get);
        return () -> {
            try {
                return chain.proceed();
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }
}
