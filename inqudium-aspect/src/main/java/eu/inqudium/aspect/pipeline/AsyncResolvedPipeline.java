package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.ResolvedPipelineState;
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
 * <p>Instances are immutable and safe for concurrent use. Call-ID generation
 * is delegated to {@link ResolvedPipelineState}, which is thread-safe by
 * construction.</p>
 *
 * @since 0.7.0
 */
public final class AsyncResolvedPipeline {

    /**
     * The pre-composed async chain factory. Takes a terminal async executor
     * and returns the fully composed chain that traverses all layers before
     * reaching the terminal.
     */
    private final Function<InternalAsyncExecutor<Void, Object>,
            InternalAsyncExecutor<Void, Object>> chainFactory;

    /**
     * Per-instance state — chain ID, call-ID source, and layer names.
     * Obtained from {@link ResolvedPipelineState#create(List)} at resolution
     * time.
     */
    private final ResolvedPipelineState state;

    // ======================== Construction ========================

    private AsyncResolvedPipeline(
            Function<InternalAsyncExecutor<Void, Object>,
                    InternalAsyncExecutor<Void, Object>> chainFactory,
            ResolvedPipelineState state) {
        this.chainFactory = chainFactory;
        this.state = state;
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
        // Resolve applicable providers once — filter and sort in a single pass
        List<? extends AsyncAspectLayerProvider<Object>> applicable = providers.stream()
                .filter(p -> p.canHandle(method))
                .sorted(Comparator.comparingInt(AsyncAspectLayerProvider::order))
                .toList();

        return fromProviders(applicable);
    }

    /**
     * Resolves and pre-composes an async pipeline from all given providers
     * without applying {@code canHandle} filtering — used when no target
     * {@link Method} is available (e.g. non-method join points, or aspects
     * that reuse the same chain for every invocation).
     *
     * <p>Providers are sorted by {@link AsyncAspectLayerProvider#order()}
     * once, then composed into the permanent chain factory. The returned
     * instance can then be invoked repeatedly with different
     * {@link JoinPointExecutor}s.</p>
     *
     * @param providers all registered async providers (will be sorted by order)
     * @return a pre-composed, reusable async pipeline
     * @throws IllegalArgumentException if {@code providers} is null
     */
    public static AsyncResolvedPipeline resolveAll(
            List<? extends AsyncAspectLayerProvider<Object>> providers) {
        if (providers == null) {
            throw new IllegalArgumentException("Providers list must not be null");
        }

        List<? extends AsyncAspectLayerProvider<Object>> sorted = providers.stream()
                .sorted(Comparator.comparingInt(AsyncAspectLayerProvider::order))
                .toList();

        return fromProviders(sorted);
    }

    /**
     * Builds an {@code AsyncResolvedPipeline} from an already filtered and sorted
     * provider list. Extracts actions and names in separate passes over the
     * same list — no redundant filtering or sorting.
     */
    private static AsyncResolvedPipeline fromProviders(
            List<? extends AsyncAspectLayerProvider<Object>> providers) {
        List<AsyncLayerAction<Void, Object>> actions = providers.stream()
                .map(AsyncAspectLayerProvider::asyncLayerAction)
                .toList();

        List<String> names = providers.stream()
                .map(AsyncAspectLayerProvider::layerName)
                .toList();

        return new AsyncResolvedPipeline(
                composeFactory(actions), ResolvedPipelineState.create(names));
    }

    /**
     * Composes the chain factory inside-out from the sorted list of async actions.
     */
    private static Function<InternalAsyncExecutor<Void, Object>,
            InternalAsyncExecutor<Void, Object>> composeFactory(
            List<AsyncLayerAction<Void, Object>> actions) {
        Function<InternalAsyncExecutor<Void, Object>,
                InternalAsyncExecutor<Void, Object>> factory = Function.identity();

        for (int i = actions.size() - 1; i >= 0; i--) {
            AsyncLayerAction<Void, Object> action = actions.get(i);
            Function<InternalAsyncExecutor<Void, Object>,
                    InternalAsyncExecutor<Void, Object>> outer = factory;

            factory = terminal -> {
                InternalAsyncExecutor<Void, Object> next = outer.apply(terminal);
                return (chainId, callId, arg) ->
                        action.executeAsync(chainId, callId, arg, next);
            };
        }
        return factory;
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
     * @param coreExecutor the join point execution (typically
     *                     {@code () -> (CompletionStage<Object>) pjp.proceed()}),
     *                     typed to enforce that the return value is a
     *                     {@link CompletionStage} at compile time
     * @return a {@link CompletionStage} carrying the result or the failure —
     *         never {@code null}, never throws
     */
    public CompletionStage<Object> execute(
            JoinPointExecutor<CompletionStage<Object>> coreExecutor) {
        long callId = state.nextCallId();
        long cid = state.chainId();

        // Terminal: invokes the typed executor and bridges into the async chain.
        // The CompletionStage return type is enforced by the method signature —
        // no runtime instanceof check needed.
        InternalAsyncExecutor<Void, Object> terminal = (c, ca, arg) -> {
            try {
                return coreExecutor.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        };

        try {
            return chainFactory.apply(terminal).executeAsync(cid, callId, null);
        } catch (CompletionException e) {
            // Unwrap transported checked exceptions from the terminal
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
        return state.chainId();
    }

    /** Returns the layer names in order (outermost first). */
    public List<String> layerNames() {
        return state.layerNames();
    }

    /** Returns the number of layers in this pipeline. */
    public int depth() {
        return state.depth();
    }

    /**
     * Returns a diagnostic string similar to
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()}.
     */
    public String toStringHierarchy() {
        return state.toStringHierarchy();
    }
}
