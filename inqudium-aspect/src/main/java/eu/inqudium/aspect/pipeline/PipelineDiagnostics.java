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
     */
    static final PipelineDiagnostics EMPTY = new PipelineDiagnostics(0L, List.of());

    private final long chainId;
    private final AtomicLong callIdCounter = new AtomicLong();
    private final List<String> layerNames;

    private PipelineDiagnostics(long chainId, List<String> layerNames) {
        this.chainId = chainId;
        this.layerNames = layerNames;
    }

    /**
     * Creates diagnostics for a resolved pipeline with a fresh chain ID.
     *
     * @param layerNames the layer names in order (outermost first), must be
     *                   an immutable list (e.g. from {@link List#of(Object[])})
     * @return a new diagnostics instance with a globally unique chain ID
     */
    static PipelineDiagnostics create(List<String> layerNames) {
        return new PipelineDiagnostics(CHAIN_ID_COUNTER.incrementAndGet(), layerNames);
    }

    /**
     * Generates the next call ID. Called once per pipeline execution.
     *
     * @return the next call ID (monotonically increasing)
     */
    long nextCallId() {
        return callIdCounter.incrementAndGet();
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
     * Returns the current (most recently generated) call ID.
     *
     * @return the latest call ID, or 0 if never executed
     */
    long currentCallId() {
        return callIdCounter.get();
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
     * @return a formatted hierarchy string
     */
    String toStringHierarchy() {
        StringBuilder sb = new StringBuilder();
        sb.append("Chain-ID: ").append(chainId)
                .append(" (current call-ID: ").append(callIdCounter.get())
                .append(")\n");

        for (int i = 0; i < layerNames.size(); i++) {
            if (i > 0) {
                sb.append("  ".repeat(i - 1)).append("  └── ");
            }
            sb.append(layerNames.get(i)).append("\n");
        }
        return sb.toString();
    }
}
