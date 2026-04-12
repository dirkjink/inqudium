package eu.inqudium.aspect.pipeline;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static eu.inqudium.core.pipeline.ChainIdGenerator.CHAIN_ID_COUNTER;

/**
 * Shared diagnostics state for pre-composed pipelines.
 *
 * <p>Both {@link ResolvedPipeline} and {@link AsyncResolvedPipeline} maintain
 * identical diagnostic information — chain ID, call-ID counter, layer names,
 * and the same {@code toStringHierarchy()} rendering. This class extracts
 * that shared state to avoid duplication.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are safe for concurrent use. The only mutable state is the
 * {@link AtomicLong} call-ID counter, which is thread-safe by design.</p>
 */
final class PipelineDiagnostics {

    /**
     * Sentinel instance for empty pipelines — avoids allocating a chain ID.
     *
     * <p>The {@link #nextCallId()} and {@link #currentCallId()} methods
     * always return 0 for this instance, so that empty pipelines across
     * the JVM do not share and mutate a single counter.</p>
     */
    static final PipelineDiagnostics EMPTY = new PipelineDiagnostics(0L, List.of(), true);

    private final long chainId;
    private final AtomicLong callIdCounter = new AtomicLong();
    private final List<String> layerNames;
    private final boolean empty;

    private PipelineDiagnostics(long chainId, List<String> layerNames, boolean empty) {
        this.chainId = chainId;
        this.layerNames = layerNames;
        this.empty = empty;
    }

    /**
     * Creates diagnostics for a resolved pipeline with a fresh chain ID.
     *
     * @param layerNames the layer names in order (outermost first), must be
     *                   an immutable list (e.g. from {@link List#of(Object[])})
     * @return a new diagnostics instance with a globally unique chain ID
     */
    static PipelineDiagnostics create(List<String> layerNames) {
        return new PipelineDiagnostics(CHAIN_ID_COUNTER.incrementAndGet(), layerNames, false);
    }

    /**
     * Generates the next call ID. Called once per pipeline execution.
     *
     * <p>Returns 0 for the {@link #EMPTY} sentinel — no counter is
     * incremented, so empty pipelines do not pollute each other's
     * diagnostics.</p>
     *
     * @return the next call ID (monotonically increasing), or 0 for empty pipelines
     */
    long nextCallId() {
        return empty ? 0L : callIdCounter.incrementAndGet();
    }

    /**
     * Returns the chain ID assigned to this pipeline.
     *
     * @return the chain ID (unique, from the global counter; 0 for empty pipelines)
     */
    long chainId() {
        return chainId;
    }

    /**
     * Returns the most recently generated call ID across all threads.
     *
     * <p><strong>Informational only.</strong> This value reflects the global
     * invocation counter for this pipeline instance. In a concurrent environment
     * it does <em>not</em> correspond to the calling thread's own call ID —
     * other threads may have incremented the counter between the caller's
     * {@code execute()} and this read. Use this method only for approximate
     * monitoring (e.g. "how many total calls has this pipeline seen?") or
     * in single-threaded test scenarios where the value is deterministic.</p>
     *
     * <p>The per-invocation call ID is passed through the chain as a parameter
     * ({@code callId} in {@code LayerAction.execute}) and is always accurate
     * within that invocation's scope.</p>
     *
     * @return the latest call ID (globally, across all threads), or 0 if
     *         never executed
     */
    long currentCallId() {
        return empty ? 0L : callIdCounter.get();
    }

    /**
     * Returns the layer names in order (outermost first).
     *
     * @return an unmodifiable list of layer names
     */
    List<String> layerNames() {
        return layerNames;
    }

    /**
     * Returns the number of layers in this pipeline.
     *
     * @return the layer count
     */
    int depth() {
        return layerNames.size();
    }

    /**
     * Returns a diagnostic string similar to
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()}.
     *
     * <p>Example output for a three-layer pipeline:</p>
     * <pre>
     * Chain-ID: 42 (current call-ID: 7)
     * AUTHORIZATION
     *   └── LOGGING
     *     └── TIMING
     * </pre>
     *
     * <p>The {@code current call-ID} in the output reflects the global
     * invocation count at the time of this call — see
     * {@link #currentCallId()} for concurrency caveats.</p>
     *
     * @return a formatted hierarchy string
     */
    String toStringHierarchy() {
        StringBuilder sb = new StringBuilder();
        sb.append("Chain-ID: ").append(chainId)
                .append(" (current call-ID: ").append(currentCallId())
                .append(")\n");

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
