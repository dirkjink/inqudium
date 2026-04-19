package eu.inqudium.core.pipeline;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Shared per-instance state for a resolved (pre-composed) pipeline.
 *
 * <p>A resolved pipeline is one whose layer structure has been folded once
 * at construction time and is reused across all subsequent invocations —
 * in contrast to lazy wrapper chains (e.g. {@code CallableWrapper},
 * {@code SupplierWrapper}) that derive their identity from the wrapper
 * hierarchy itself. The state owned by this class is:</p>
 * <ul>
 *   <li>a globally-unique {@link #chainId() chain ID},</li>
 *   <li>an instance-local {@link #nextCallId() call-ID source},</li>
 *   <li>the list of {@link #layerNames() layer names} that make up the
 *       pipeline, and</li>
 *   <li>a formatted {@link #toStringHierarchy() hierarchy rendering} for
 *       diagnostics output.</li>
 * </ul>
 *
 * <p>Used by {@code ResolvedPipeline}, {@code AsyncResolvedPipeline},
 * {@code SyncPipelineTerminal}, and {@code HybridAspectPipelineTerminal}.
 * Lazy wrapper chains ({@code AbstractBaseWrapper} and its subclasses) are
 * not in scope — they maintain their own {@code chainId}/{@code callId}
 * state through the wrapper hierarchy. Standalone executions
 * ({@code InqExecutor}, {@code InqAsyncExecutor}) are also out of scope —
 * they draw IDs directly from {@link PipelineIds}.</p>
 *
 * <h3>Call-ID source — instance-local</h3>
 * <p>Each resolved pipeline keeps its own {@link LongSupplier} obtained from
 * {@link PipelineIds#newInstanceCallIdSource()}, so the per-pipeline sequence
 * stays independent and threads calling different pipelines do not contend
 * on a shared counter cache-line.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are safe for concurrent use. The call-ID source is backed by
 * an {@link java.util.concurrent.atomic.AtomicLong}, which is thread-safe
 * by design.</p>
 *
 * @since 0.7.0
 */
public final class ResolvedPipelineState {

    /**
     * Always-zero supplier used by the {@link #EMPTY} sentinel. A shared
     * capture-less lambda — one instance, no per-call allocation.
     */
    private static final LongSupplier ZERO_SUPPLIER = () -> 0L;

    /**
     * Sentinel instance for empty pipelines — avoids allocating a chain ID
     * or a counter. The {@link #nextCallId()} method returns 0 for this
     * instance, so empty pipelines across the JVM do not share and mutate
     * a single counter.
     */
    public static final ResolvedPipelineState EMPTY =
            new ResolvedPipelineState(0L, ZERO_SUPPLIER, List.of());

    private final long chainId;
    private final LongSupplier callIdSource;
    private final List<String> layerNames;

    private ResolvedPipelineState(long chainId, LongSupplier callIdSource,
                                  List<String> layerNames) {
        this.chainId = chainId;
        this.callIdSource = callIdSource;
        this.layerNames = layerNames;
    }

    /**
     * Creates a state holder for a resolved pipeline with a fresh chain ID
     * and an independent instance-local call-ID source.
     *
     * @param layerNames the layer names in order (outermost first), must be
     *                   an immutable list (e.g. from {@link List#of(Object[])})
     * @return a new state instance with a globally unique chain ID and its
     * own private call-ID counter
     */
    public static ResolvedPipelineState create(List<String> layerNames) {
        return new ResolvedPipelineState(
                PipelineIds.nextChainId(),
                PipelineIds.newInstanceCallIdSource(),
                layerNames);
    }

    /**
     * Generates the next call ID for this pipeline instance.
     *
     * <p>Returns 0 for the {@link #EMPTY} sentinel (its call-ID source is
     * hard-wired to zero).</p>
     *
     * @return the next call ID (monotonically increasing within this
     * pipeline), or 0 for empty pipelines
     */
    public long nextCallId() {
        return callIdSource.getAsLong();
    }

    /**
     * Returns the chain ID assigned to this pipeline.
     *
     * @return the chain ID (unique across the JVM; 0 for empty pipelines)
     */
    public long chainId() {
        return chainId;
    }

    /**
     * Returns the layer names in order (outermost first).
     *
     * @return an unmodifiable list of layer names
     */
    public List<String> layerNames() {
        return layerNames;
    }

    /**
     * Returns the number of layers in this pipeline.
     *
     * @return the layer count
     */
    public int depth() {
        return layerNames.size();
    }

    /**
     * Returns a diagnostic string similar to
     * {@link Wrapper#toStringHierarchy()}.
     *
     * <p>Example output for a three-layer pipeline:</p>
     * <pre>
     * Chain-ID: 42
     * AUTHORIZATION
     *   └── LOGGING
     *     └── TIMING
     * </pre>
     *
     * @return a formatted hierarchy string
     */
    public String toStringHierarchy() {
        StringBuilder sb = new StringBuilder();
        sb.append("Chain-ID: ").append(chainId).append("\n");

        if (layerNames.isEmpty()) {
            sb.append("(empty — no layers)\n");
            return sb.toString();
        }

        for (int i = 0; i < layerNames.size(); i++) {
            if (i > 0) {
                sb.append("  ".repeat(i - 1)).append("  └── ");
            }
            sb.append(layerNames.get(i)).append("\n");
        }
        return sb.toString();
    }
}
