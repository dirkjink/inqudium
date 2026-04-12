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

/**
 * A pre-composed, immutable async pipeline that can be executed repeatedly with
 * different {@link JoinPointExecutor} instances — without rebuilding the chain
 * structure on every call.
 *
 * <p>The async counterpart to {@link ResolvedPipeline}. Applies the same
 * zero-allocation optimization: the {@link AsyncLayerAction} chain is
 * materialized once as a permanent linked structure of
 * {@link InternalAsyncExecutor} lambdas. The innermost lambda reads the
 * actual terminal from a {@link ThreadLocal} slot set at the start of each
 * {@link #execute} call.</p>
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
 * <p>Instances are safe for concurrent use. The pre-built chain is immutable;
 * the {@link ThreadLocal} terminal slot provides per-thread isolation; the
 * call-ID counter is an {@link java.util.concurrent.atomic.AtomicLong}.</p>
 *
 * @since 0.7.0
 */
public final class AsyncResolvedPipeline {

    /**
     * Sentinel instance for methods with no applicable async layers.
     */
    private static final AsyncResolvedPipeline EMPTY = new AsyncResolvedPipeline(
            null, null, PipelineDiagnostics.EMPTY);

    /**
     * Pre-built async chain head — the outermost executor. All chain lambdas
     * are permanent objects created once during {@link #fromProviders}. The
     * innermost lambda (terminal proxy) reads the actual terminal from
     * {@link #terminalSlot}.
     *
     * <p>{@code null} for empty pipelines.</p>
     */
    private final InternalAsyncExecutor<Void, Object> head;

    /**
     * Thread-local slot for the per-invocation terminal executor.
     *
     * <p>{@code null} for empty pipelines.</p>
     */
    private final ThreadLocal<InternalAsyncExecutor<Void, Object>> terminalSlot;

    private final PipelineDiagnostics diagnostics;

    // ======================== Construction ========================

    private AsyncResolvedPipeline(
            InternalAsyncExecutor<Void, Object> head,
            ThreadLocal<InternalAsyncExecutor<Void, Object>> terminalSlot,
            PipelineDiagnostics diagnostics) {
        this.head = head;
        this.terminalSlot = terminalSlot;
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
     * Builds an {@code AsyncResolvedPipeline} from an already filtered and
     * sorted provider list.
     *
     * <p>Creates a permanent chain of {@link InternalAsyncExecutor} lambdas
     * in a single reverse pass. The innermost lambda is a "terminal proxy"
     * that reads the actual terminal from a {@link ThreadLocal} slot at
     * execution time.</p>
     */
    private static AsyncResolvedPipeline fromProviders(
            List<? extends AsyncAspectLayerProvider<Object>> providers) {
        if (providers.isEmpty()) {
            return EMPTY;
        }

        int size = providers.size();
        String[] names = new String[size];

        ThreadLocal<InternalAsyncExecutor<Void, Object>> slot = new ThreadLocal<>();

        // Terminal proxy — permanent lambda that reads the actual terminal
        // from the ThreadLocal slot at execution time
        InternalAsyncExecutor<Void, Object> current = (cid, callId, arg) ->
                slot.get().executeAsync(cid, callId, arg);

        // Build chain once in reverse — permanent lambdas, not per-call
        for (int i = size - 1; i >= 0; i--) {
            AsyncAspectLayerProvider<Object> p = providers.get(i);
            names[i] = p.layerName();
            AsyncLayerAction<Void, Object> action = p.asyncLayerAction();
            InternalAsyncExecutor<Void, Object> next = current;
            current = (cid, callId, arg) ->
                    action.executeAsync(cid, callId, arg, next);
        }

        return new AsyncResolvedPipeline(current, slot,
                PipelineDiagnostics.create(List.of(names)));
    }

    // ======================== Execution ========================

    /**
     * Executes the pre-composed async pipeline with the given join point executor.
     *
     * <p>This method <strong>never throws</strong>. All exceptions — whether from the
     * synchronous start phase or the asynchronous completion phase — are delivered
     * through the returned {@link CompletionStage}.</p>
     *
     * <p>Per-call cost: one terminal lambda, one {@code ThreadLocal.set/remove}
     * pair, and the chain traversal. No chain-lambda allocations.</p>
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
            if (head == null) {
                return terminal.executeAsync(diagnostics.chainId(), callId, null);
            }
            try {
                terminalSlot.set(terminal);
                return head.executeAsync(diagnostics.chainId(), callId, null);
            } finally {
                terminalSlot.remove();
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            return CompletableFuture.failedFuture(cause != null ? cause : e);
        } catch (Throwable e) {
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
