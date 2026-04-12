package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.Throws;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * A pre-composed, immutable async pipeline that can be executed repeatedly with
 * different {@link JoinPointExecutor} instances — without rebuilding the chain
 * structure on every call.
 *
 * <p>The async counterpart to {@link ResolvedPipeline}. Applies the same
 * optimization: the {@link AsyncLayerAction} chain is composed into a single
 * {@code Function<InternalAsyncExecutor, InternalAsyncExecutor>} at resolution
 * time. At invocation time, only the terminal executor is created.</p>
 *
 * <h3>Two-phase execution</h3>
 * <p>Each async layer has two phases:</p>
 * <ul>
 *   <li><strong>Start phase</strong> (synchronous): code before
 *       {@code next.executeAsync()} runs on the calling thread.</li>
 *   <li><strong>End phase</strong> (asynchronous): code attached via
 *       {@code whenComplete()}, {@code thenApply()}, etc. runs when the
 *       async operation completes.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable and safe for concurrent use. The only mutable
 * state is the call-ID counter inside {@link PipelineDiagnostics}, which is
 * thread-safe by design.</p>
 *
 * @since 0.7.0
 */
public final class AsyncResolvedPipeline {

    /**
     * Sentinel instance for methods with no applicable async layers.
     */
    private static final AsyncResolvedPipeline EMPTY = new AsyncResolvedPipeline(
            Function.identity(), PipelineDiagnostics.EMPTY);

    private final Function<InternalAsyncExecutor<Void, Object>,
            InternalAsyncExecutor<Void, Object>> chainFactory;

    private final PipelineDiagnostics diagnostics;

    // ======================== Construction ========================

    private AsyncResolvedPipeline(
            Function<InternalAsyncExecutor<Void, Object>,
                    InternalAsyncExecutor<Void, Object>> chainFactory,
            PipelineDiagnostics diagnostics) {
        this.chainFactory = chainFactory;
        this.diagnostics = diagnostics;
    }

    /**
     * Resolves and pre-composes an async pipeline from the given providers,
     * filtered by the target method.
     *
     * @param providers all registered async providers (will be filtered and sorted)
     * @param method    the target method for {@code canHandle} filtering
     * @return a pre-composed, reusable async pipeline
     */
    public static AsyncResolvedPipeline resolve(
            List<? extends AsyncAspectLayerProvider<Object>> providers,
            Method method) {
        List<? extends AsyncAspectLayerProvider<Object>> applicable = providers.stream()
                .filter(p -> p.canHandle(method))
                .sorted(Comparator.comparingInt(AsyncAspectLayerProvider::order))
                .toList();

        return fromProviders(applicable);
    }

    /**
     * Builds an {@code AsyncResolvedPipeline} from an already filtered and sorted
     * provider list.
     *
     * <p>Composes the chain factory and collects layer names in a single
     * reverse pass — no intermediate action or name lists are created.
     * Each provider is visited exactly once.</p>
     */
    private static AsyncResolvedPipeline fromProviders(
            List<? extends AsyncAspectLayerProvider<Object>> providers) {
        if (providers.isEmpty()) {
            return EMPTY;
        }

        int size = providers.size();
        String[] names = new String[size];

        Function<InternalAsyncExecutor<Void, Object>,
                InternalAsyncExecutor<Void, Object>> factory = Function.identity();

        for (int i = size - 1; i >= 0; i--) {
            AsyncAspectLayerProvider<Object> p = providers.get(i);
            names[i] = p.layerName();
            AsyncLayerAction<Void, Object> action = p.asyncLayerAction();
            Function<InternalAsyncExecutor<Void, Object>,
                    InternalAsyncExecutor<Void, Object>> outer = factory;

            factory = terminal -> {
                InternalAsyncExecutor<Void, Object> next = outer.apply(terminal);
                return (chainId, callId, arg) ->
                        action.executeAsync(chainId, callId, arg, next);
            };
        }

        return new AsyncResolvedPipeline(
                factory, PipelineDiagnostics.create(List.of(names)));
    }

    // ======================== Execution ========================

    /**
     * Executes the pre-composed async pipeline with the given join point executor.
     *
     * <p>This method <strong>never throws</strong>. All exceptions — whether from the
     * synchronous start phase or the asynchronous completion phase — are delivered
     * through the returned {@link CompletionStage}. This guarantees a uniform error
     * channel for callers:</p>
     *
     * <ul>
     *   <li><strong>Synchronous failures</strong> (e.g. a layer's start-phase throws
     *       before creating a {@code CompletionStage}) are caught and wrapped in a
     *       {@link java.util.concurrent.CompletableFuture#failedFuture failed future}.</li>
     *   <li><strong>Asynchronous failures</strong> (e.g. the downstream
     *       {@code CompletionStage} completes exceptionally) propagate naturally
     *       through the returned stage.</li>
     * </ul>
     *
     * <p>This eliminates a common pitfall for {@link AsyncAspectLayerProvider}
     * implementors: a {@code throw new IllegalArgumentException()} in a layer's
     * start phase is delivered through the same channel as a
     * {@code CompletableFuture.failedFuture(new IllegalArgumentException())},
     * so the caller can handle both uniformly via {@code .exceptionally()},
     * {@code .handle()}, or {@code .whenComplete()}.</p>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed}),
     *                     whose return value must be a {@link CompletionStage}
     * @return a {@link CompletionStage} carrying the result or the failure —
     *         never {@code null}, never throws
     */
    @SuppressWarnings("unchecked")
    public CompletionStage<Object> execute(JoinPointExecutor<Object> coreExecutor) {
        long callId = diagnostics.nextCallId();

        InternalAsyncExecutor<Void, Object> terminal = (cid, caid, arg) -> {
            Object result;
            try {
                result = coreExecutor.proceed();
            } catch (Throwable t) {
                throw Throws.wrapChecked(t);
            }

            if (result == null) {
                // Technically valid — a method may return null instead of an
                // empty future. Translate to a safely completed stage.
                return CompletableFuture.completedFuture(null);
            }

            if (result instanceof CompletionStage<?> stage) {
                return (CompletionStage<Object>) stage;
            }

            throw new IllegalStateException(
                    "AsyncResolvedPipeline expected the proxied method to return a "
                            + "CompletionStage, but received: "
                            + result.getClass().getName()
                            + ". Ensure this aspect is only applied to methods returning "
                            + "CompletionStage or CompletableFuture. Use canHandle(Method) "
                            + "to restrict the async pipeline to compatible methods.");
        };

        try {
            return chainFactory.apply(terminal)
                    .executeAsync(diagnostics.chainId(), callId, null);
        } catch (CompletionException e) {
            // Unwrap transported checked exceptions from the terminal.
            // Guard against null cause — if absent, deliver the
            // CompletionException itself rather than producing a confusing NPE.
            Throwable cause = e.getCause();
            return CompletableFuture.failedFuture(cause != null ? cause : e);
        } catch (Throwable e) {
            // Any synchronous exception from a layer's start phase is
            // converted to a failed future — uniform error channel
            return CompletableFuture.failedFuture(e);
        }
    }

    // ======================== Diagnostics ========================

    /** Returns the chain ID assigned to this resolved pipeline. */
    public long chainId() {
        return diagnostics.chainId();
    }

    /**
     * Returns the most recently generated call ID across all threads.
     *
     * <p><strong>Informational only.</strong> In concurrent environments this
     * value does not correspond to any specific thread's call — see
     * {@link PipelineDiagnostics#currentCallId()} for details.</p>
     */
    public long currentCallId() {
        return diagnostics.currentCallId();
    }

    /** Returns the layer names in order (outermost first). */
    public List<String> layerNames() {
        return diagnostics.layerNames();
    }

    /** Returns the number of layers in this pipeline. */
    public int depth() {
        return diagnostics.depth();
    }

    /**
     * Returns a diagnostic string similar to
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()}.
     */
    public String toStringHierarchy() {
        return diagnostics.toStringHierarchy();
    }
}
