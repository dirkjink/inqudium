package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static eu.inqudium.core.pipeline.ChainIdGenerator.CHAIN_ID_COUNTER;

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
 * state is the {@link AtomicLong} call-ID counter, which is thread-safe
 * by design.</p>
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
     * Unique chain ID, drawn from the same global counter as wrapper chains.
     */
    private final long chainId;

    /**
     * Shared call-ID counter — incremented once per invocation.
     */
    private final AtomicLong callIdCounter = new AtomicLong();

    /**
     * Layer names in order (outermost first), retained for diagnostics.
     */
    private final List<String> layerNames;

    // ======================== Construction ========================

    private AsyncResolvedPipeline(
            Function<InternalAsyncExecutor<Void, Object>,
                    InternalAsyncExecutor<Void, Object>> chainFactory,
            long chainId,
            List<String> layerNames) {
        this.chainFactory = chainFactory;
        this.chainId = chainId;
        this.layerNames = layerNames;
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
                composeFactory(actions), CHAIN_ID_COUNTER.incrementAndGet(), names);
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
        long callId = callIdCounter.incrementAndGet();

        // Terminal: invokes the typed executor and bridges into the async chain.
        // The CompletionStage return type is enforced by the method signature —
        // no runtime instanceof check needed.
        InternalAsyncExecutor<Void, Object> terminal = (cid, caid, arg) -> {
            try {
                return coreExecutor.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        };

        try {
            return chainFactory.apply(terminal).executeAsync(chainId, callId, null);
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
        return chainId;
    }

    /** Returns the current (most recently generated) call ID. */
    public long currentCallId() {
        return callIdCounter.get();
    }

    /** Returns the layer names in order (outermost first). */
    public List<String> layerNames() {
        return layerNames;
    }

    /** Returns the number of layers in this pipeline. */
    public int depth() {
        return layerNames.size();
    }

    /**
     * Returns a diagnostic string similar to
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()}.
     */
    public String toStringHierarchy() {
        StringBuilder sb = new StringBuilder();
        sb.append("Chain-ID: ").append(chainId)
                .append(" (current call-ID: ").append(callIdCounter.get())
                .append(")\n");

        for (int i = 0; i < layerNames.size(); i++) {
            if (i > 0) {
                sb.repeat("  ", i - 1).append("  └── ");
            }
            sb.append(layerNames.get(i)).append("\n");
        }
        return sb.toString();
    }
}
