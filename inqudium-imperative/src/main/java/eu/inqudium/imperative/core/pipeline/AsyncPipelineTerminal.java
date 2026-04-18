package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Asynchronous terminal for an {@link InqPipeline}.
 *
 * <p>Takes a paradigm-agnostic pipeline and provides async execution methods
 * by folding the elements via {@link InqAsyncDecorator#decorateAsyncJoinPoint}.
 * Each element in the pipeline must implement {@link InqAsyncDecorator} — if
 * an element does not, a descriptive {@link ClassCastException} is thrown at
 * chain-build time.</p>
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
 * <p>Instances are immutable and safe for concurrent use. The decorated
 * chain is built on each call to {@code decorateJoinPoint()}.</p>
 *
 * @since 0.8.0
 */
public final class AsyncPipelineTerminal {

    private final InqPipeline pipeline;

    private AsyncPipelineTerminal(InqPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Creates an async terminal for the given pipeline.
     *
     * @param pipeline the composed pipeline
     * @return the async terminal
     * @throws NullPointerException if pipeline is null
     */
    public static AsyncPipelineTerminal of(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "Pipeline must not be null");
        return new AsyncPipelineTerminal(pipeline);
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
     * <p>Each element in the pipeline is folded via
     * {@link InqAsyncDecorator#decorateAsyncJoinPoint}, producing a nested chain
     * where the outermost element intercepts first:</p>
     * <pre>
     *   outermost.decorateAsyncJoinPoint(
     *       middle.decorateAsyncJoinPoint(
     *           innermost.decorateAsyncJoinPoint(executor)))
     * </pre>
     *
     * @param executor the core async execution at the bottom of the chain
     * @param <R>      the result type carried by the CompletionStage
     * @return the decorated async executor
     * @throws ClassCastException if any element does not implement {@link InqAsyncDecorator}
     */
    public <R> JoinPointExecutor<CompletionStage<R>> decorateJoinPoint(
            JoinPointExecutor<CompletionStage<R>> executor) {
        return pipeline.chain(executor, (downstream, element) ->
                asAsyncDecorator(element).decorateAsyncJoinPoint(downstream));
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

    // ======================== Internal ========================

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
}
