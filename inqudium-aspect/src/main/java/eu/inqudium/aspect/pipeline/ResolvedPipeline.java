package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.Throws;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * A pre-composed, immutable pipeline that can be executed repeatedly with
 * different {@link JoinPointExecutor} instances — without rebuilding the
 * chain structure on every call.
 *
 * <h3>Motivation</h3>
 * <p>The original {@link AspectPipelineBuilder} creates a full
 * {@link eu.inqudium.core.pipeline.JoinPointWrapper} chain on every
 * invocation. This is wasteful: the layer structure (which providers, in
 * what order, with what actions) is <strong>deterministic per Method</strong>.
 * Only the terminal executor ({@code pjp::proceed}) changes between calls.</p>
 *
 * <h3>Zero-allocation hot path</h3>
 * <p>The layer chain is materialized <strong>once</strong> at resolution time
 * as a permanent linked structure of {@link InternalExecutor} lambdas. The
 * innermost lambda (the "terminal proxy") reads the actual terminal from a
 * {@link ThreadLocal} slot that is set at the start of each {@link #execute}
 * call and removed in a {@code finally} block.</p>
 *
 * <p>This eliminates all per-invocation object allocations beyond the single
 * terminal lambda that captures {@code pjp::proceed}:</p>
 * <table>
 *   <tr><th></th><th>JoinPointWrapper chain</th><th>ResolvedPipeline</th></tr>
 *   <tr><td>Filter providers</td><td>every call</td><td>once</td></tr>
 *   <tr><td>Sort providers</td><td>every call</td><td>once</td></tr>
 *   <tr><td>Compose chain</td><td>N wrapper objects</td><td>0 (pre-built)</td></tr>
 *   <tr><td>Terminal executor</td><td>1 lambda</td><td>1 lambda</td></tr>
 *   <tr><td>Chain lambdas</td><td>—</td><td>0 (permanent)</td></tr>
 * </table>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are safe for concurrent use. The pre-built chain is immutable;
 * the {@link ThreadLocal} terminal slot provides per-thread isolation; the
 * call-ID counter is an {@link java.util.concurrent.atomic.AtomicLong}.</p>
 *
 * @since 0.7.0
 */
public final class ResolvedPipeline {

    /**
     * Sentinel instance for methods with no applicable layers.
     */
    private static final ResolvedPipeline EMPTY = new ResolvedPipeline(
            null, null, PipelineDiagnostics.EMPTY);

    /**
     * Pre-built chain head — the outermost executor. All chain lambdas are
     * permanent objects created once during {@link #fromProviders}. The
     * innermost lambda (terminal proxy) reads the actual terminal from
     * {@link #terminalSlot}.
     *
     * <p>{@code null} for empty pipelines (no applicable layers).</p>
     */
    private final InternalExecutor<Void, Object> head;

    /**
     * Thread-local slot for the per-invocation terminal executor. Set before
     * chain execution, read by the terminal proxy lambda, removed in a
     * {@code finally} block after execution completes.
     *
     * <p>{@code null} for empty pipelines.</p>
     */
    private final ThreadLocal<InternalExecutor<Void, Object>> terminalSlot;

    private final PipelineDiagnostics diagnostics;

    // ======================== Construction ========================

    private ResolvedPipeline(
            InternalExecutor<Void, Object> head,
            ThreadLocal<InternalExecutor<Void, Object>> terminalSlot,
            PipelineDiagnostics diagnostics) {
        this.head = head;
        this.terminalSlot = terminalSlot;
        this.diagnostics = diagnostics;
    }

    /**
     * Resolves and pre-composes a pipeline from the given providers, filtered
     * by the target method.
     *
     * <p>This is the main factory method. It performs filtering, sorting, and
     * chain construction <strong>once</strong>. The returned instance can then
     * be invoked repeatedly with different {@link JoinPointExecutor}s without
     * any per-call allocations beyond the terminal lambda.</p>
     *
     * @param providers all registered providers (will be filtered and sorted)
     * @param method    the target method for {@code canHandle} filtering
     * @return a pre-composed, reusable pipeline
     */
    public static ResolvedPipeline resolve(List<? extends AspectLayerProvider<Object>> providers,
                                           Method method) {
        List<? extends AspectLayerProvider<Object>> applicable = providers.stream()
                .filter(p -> p.canHandle(method))
                .sorted(Comparator.comparingInt(AspectLayerProvider::order))
                .toList();

        return fromProviders(applicable);
    }

    /**
     * Builds a {@code ResolvedPipeline} from an already filtered and sorted
     * provider list.
     *
     * <p>Creates a permanent chain of {@link InternalExecutor} lambdas in a
     * single reverse pass. The innermost lambda is a "terminal proxy" that
     * reads the actual terminal from a {@link ThreadLocal} slot at execution
     * time — this allows the chain structure to be built once and reused
     * across all invocations without per-call allocations.</p>
     */
    private static ResolvedPipeline fromProviders(
            List<? extends AspectLayerProvider<Object>> providers) {
        if (providers.isEmpty()) {
            return EMPTY;
        }

        int size = providers.size();
        String[] names = new String[size];

        ThreadLocal<InternalExecutor<Void, Object>> slot = new ThreadLocal<>();

        // Terminal proxy — permanent lambda that reads the actual terminal
        // from the ThreadLocal slot at execution time. This is the only
        // point where the per-call terminal enters the pre-built chain.
        InternalExecutor<Void, Object> current = (cid, callId, arg) ->
                slot.get().execute(cid, callId, arg);

        // Build chain once in reverse — each lambda captures the next
        // executor by reference. These are permanent objects, not per-call.
        for (int i = size - 1; i >= 0; i--) {
            AspectLayerProvider<Object> p = providers.get(i);
            names[i] = p.layerName();
            LayerAction<Void, Object> action = p.layerAction();
            InternalExecutor<Void, Object> next = current;
            current = (cid, callId, arg) -> action.execute(cid, callId, arg, next);
        }

        return new ResolvedPipeline(current, slot,
                PipelineDiagnostics.create(List.of(names)));
    }

    // ======================== Execution ========================

    /**
     * Executes the pre-composed pipeline with the given join point executor.
     *
     * <p>Per-call cost: one terminal lambda creation, one
     * {@code AtomicLong.incrementAndGet()}, one {@code ThreadLocal.set/remove}
     * pair, and the chain traversal itself. No chain-lambda allocations —
     * the pre-built chain is reused as-is.</p>
     *
     * <p>Checked exceptions from the delegate are transported via
     * {@link CompletionException} and unwrapped before re-throwing, preserving
     * the same contract as {@link eu.inqudium.core.pipeline.JoinPointWrapper#proceed()}.</p>
     *
     * @param coreExecutor the join point execution (typically {@code pjp::proceed})
     * @return the result of the pipeline execution
     * @throws Throwable any exception from the delegate or from layer actions
     */
    public Object execute(JoinPointExecutor<Object> coreExecutor) throws Throwable {
        long callId = diagnostics.nextCallId();

        InternalExecutor<Void, Object> terminal = (cid, caid, arg) -> {
            try {
                return coreExecutor.proceed();
            } catch (Throwable t) {
                throw Throws.wrapChecked(t);
            }
        };

        try {
            if (head == null) {
                // Empty pipeline — execute terminal directly, no chain
                return terminal.execute(diagnostics.chainId(), callId, null);
            }
            try {
                terminalSlot.set(terminal);
                return head.execute(diagnostics.chainId(), callId, null);
            } finally {
                terminalSlot.remove();
            }
        } catch (CompletionException e) {
            throw Throws.unwrapAndRethrow(e);
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
     *
     * @return a formatted hierarchy string
     */
    public String toStringHierarchy() {
        return diagnostics.toStringHierarchy();
    }
}
