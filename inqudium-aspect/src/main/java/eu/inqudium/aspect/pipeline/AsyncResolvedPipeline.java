package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * A pre-composed, immutable async pipeline that can be executed repeatedly
 * with different {@link JoinPointExecutor} instances — without rebuilding
 * the chain structure on every call.
 *
 * <p>Async counterpart to {@link ResolvedPipeline}, using the same
 * array-based composition pattern. The per-call cost is a single reverse
 * loop over the pre-built {@link AsyncLayerAction} array — no
 * {@code Function.apply()} dispatch, no cascade unwinding.</p>
 *
 * <h3>Two-phase execution semantics</h3>
 * <p>Each async layer has two phases:</p>
 * <ul>
 *   <li><strong>Start phase</strong> (synchronous): runs on the calling
 *       thread before {@code next.executeAsync()} returns.</li>
 *   <li><strong>End phase</strong> (asynchronous): attached via
 *       {@code whenComplete()}, {@code thenApply()}, etc. — runs when the
 *       downstream stage completes.</li>
 * </ul>
 *
 * <h3>Error channel</h3>
 * <p>{@link #execute(JoinPointExecutor)} <strong>never throws</strong>. All
 * failures — whether from a layer's synchronous start phase or from the
 * asynchronous completion — are delivered through the returned
 * {@link CompletionStage}.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are safe for concurrent use. The action array is immutable;
 * chain composition is purely stack-local; the call-ID counter lives in
 * {@link PipelineDiagnostics} and is backed by an {@link java.util.concurrent.atomic.AtomicLong}.
 * No {@code ThreadLocal} or shared mutable state — safe for virtual threads
 * and reactive pipelines.</p>
 *
 * @since 0.7.0
 */
public final class AsyncResolvedPipeline {

    /**
     * Sentinel empty action array — avoids null checks on the execution path.
     */
    private static final AsyncLayerAction<Void, Object>[] EMPTY_ACTIONS = newActionArray(0);

    /**
     * Sentinel instance for methods with no applicable async layers.
     */
    private static final AsyncResolvedPipeline EMPTY = new AsyncResolvedPipeline(
            EMPTY_ACTIONS, PipelineDiagnostics.EMPTY);

    /**
     * Pre-built, immutable array of async layer actions in execution order
     * (outermost first). Extracted from providers once during
     * {@link #fromProviders} — no per-call provider access.
     */
    private final AsyncLayerAction<Void, Object>[] actions;

    private final PipelineDiagnostics diagnostics;

    // ======================== Construction ========================

    private AsyncResolvedPipeline(AsyncLayerAction<Void, Object>[] actions,
                                  PipelineDiagnostics diagnostics) {
        this.actions = actions;
        this.diagnostics = diagnostics;
    }

    /**
     * Resolves and pre-composes an async pipeline from the given providers,
     * filtered by the target method.
     *
     * @param providers all registered async providers (will be filtered and sorted)
     * @param method    the target method for {@code canHandle} filtering
     * @return a pre-composed, reusable async pipeline
     * @throws IllegalArgumentException if providers or method is null
     */
    public static AsyncResolvedPipeline resolve(
            List<? extends AsyncAspectLayerProvider<Object>> providers,
            Method method) {
        if (providers == null) {
            throw new IllegalArgumentException("Providers list must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("Method must not be null");
        }

        List<? extends AsyncAspectLayerProvider<Object>> applicable = providers.stream()
                .filter(p -> p.canHandle(method))
                .sorted(Comparator.comparingInt(AsyncAspectLayerProvider::order))
                .toList();

        return fromProviders(applicable);
    }

    /**
     * Resolves and pre-composes an async pipeline from the given providers
     * without applying any {@code canHandle(Method)} filter.
     *
     * <p>Used by
     * {@link AbstractAsyncPipelineAspect#executeThroughAsync(JoinPointExecutor)}
     * when no target method is available. The resulting pipeline includes
     * all providers sorted by {@link AsyncAspectLayerProvider#order()}.</p>
     *
     * @param providers all registered async providers (will be sorted, not filtered)
     * @return a pre-composed, reusable async pipeline containing every provider
     * @throws IllegalArgumentException if providers is null
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
     * Builds an {@code AsyncResolvedPipeline} from an already filtered and
     * sorted provider list. Names go into an {@link ArrayList} sized exactly
     * and wrapped unmodifiable — this avoids the array copy performed by
     * {@code List.of(E...)} when called with an array argument.
     */
    private static AsyncResolvedPipeline fromProviders(
            List<? extends AsyncAspectLayerProvider<Object>> providers) {
        if (providers.isEmpty()) {
            return EMPTY;
        }

        int size = providers.size();
        AsyncLayerAction<Void, Object>[] acts = newActionArray(size);
        List<String> names = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            AsyncAspectLayerProvider<Object> p = providers.get(i);
            acts[i] = p.asyncLayerAction();
            names.add(p.layerName());
        }

        return new AsyncResolvedPipeline(acts,
                PipelineDiagnostics.create(Collections.unmodifiableList(names)));
    }

    @SuppressWarnings("unchecked")
    private static AsyncLayerAction<Void, Object>[] newActionArray(int size) {
        return new AsyncLayerAction[size];
    }

    // ======================== Execution ========================

    /**
     * Executes the pre-composed async pipeline with the given join point
     * executor. Never throws — all failures are delivered through the
     * returned {@link CompletionStage}.
     *
     * @param coreExecutor the join point execution (typically
     *                     {@code () -> (CompletionStage<Object>) pjp.proceed()})
     * @return a {@link CompletionStage} carrying the result or the failure —
     *         never {@code null}, never throws
     */
    public CompletionStage<Object> execute(
            JoinPointExecutor<CompletionStage<Object>> coreExecutor) {
        long callId = diagnostics.nextCallId();
        long cid = diagnostics.chainId();

        // Terminal: invokes the typed executor and bridges into the async chain.
        InternalAsyncExecutor<Void, Object> current = (c, ca, a) -> {
            try {
                return coreExecutor.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                // Transport checked exceptions via CompletionException; unwrapped below
                throw new CompletionException(t);
            }
        };

        // Compose chain inside-out from the pre-built action array.
        // Each lambda captures only 2 references: the action and next.
        for (int i = actions.length - 1; i >= 0; i--) {
            AsyncLayerAction<Void, Object> action = actions[i];
            InternalAsyncExecutor<Void, Object> next = current;
            current = (c, ca, a) -> action.executeAsync(c, ca, a, next);
        }

        try {
            return current.executeAsync(cid, callId, null);
        } catch (CompletionException e) {
            // Unwrap transported checked exceptions from the terminal
            Throwable cause = e.getCause();
            return CompletableFuture.failedFuture(cause != null ? cause : e);
        } catch (Throwable e) {
            // Any synchronous exception from a layer's start phase becomes
            // a failed future — uniform error channel for callers.
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
     * <strong>Informational only.</strong>
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
