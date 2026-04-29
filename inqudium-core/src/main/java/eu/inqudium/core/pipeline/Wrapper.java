package eu.inqudium.core.pipeline;

/**
 * Core interface for the wrapper chain pattern.
 *
 * <p>A {@code Wrapper} represents a single layer in a chain of decorators. The chain
 * is traversed inward — from the outermost layer toward the core delegate — during
 * both execution and hierarchy visualization.</p>
 *
 * <p>The design is intentionally <strong>immutable</strong>: once a chain is constructed,
 * the layer relationships are fixed. This allows the same inner wrapper to safely
 * participate in multiple independent chains.</p>
 *
 * @param <S> the concrete self-type of the wrapper (recursive generic bound).
 *            This self-type parameter ensures that {@link #inner()} returns the
 *            same concrete type as the outermost wrapper, enabling type-safe
 *            chain traversal without manual casting.
 */
public interface Wrapper<S extends Wrapper<S>> {

    /**
     * Returns the next inner layer in the wrapper chain, or {@code null} if this
     * layer is the innermost one (i.e. the layer directly above the core delegate).
     *
     * <p>Implementations must only return a non-null value when the delegate is
     * itself an instance of the same concrete wrapper type. This preserves type
     * safety and prevents mixed-type chains from leaking through the traversal API.</p>
     *
     * @return the next inner {@code Wrapper} layer, or {@code null} at the chain's end
     */
    S inner();

    /**
     * Returns the unique chain identifier shared by all layers wrapping the same
     * core delegate.
     *
     * <p>This ID is assigned once when the innermost wrapper is constructed
     * (via {@link PipelineIds#nextChainId()}) and then inherited by every outer
     * layer. It serves as a zero-allocation correlation key for tracing and
     * logging — all layers in the same chain produce the same {@code chainId}.</p>
     *
     * @return a primitive {@code long} chain identifier (never boxed)
     */
    long chainId();

    /**
     * Returns a human-readable description of this specific layer.
     *
     * <p>Typically formatted as {@code "ELEMENT_TYPE(name)"} for decorator-based
     * layers, or a plain name string for layers created with explicit names.
     * Used in {@link #toStringHierarchy()} output and diagnostic logging.</p>
     *
     * @return a descriptive label for this layer
     */
    String layerDescription();

    /**
     * Renders the entire wrapper hierarchy as a formatted tree string,
     * starting from this layer and traversing inward via {@link #inner()}.
     *
     * <p>The output includes the chain ID as a header, followed by each layer's
     * description indented with tree-drawing characters. Example output:</p>
     * <pre>
     * Chain-ID: 42
     * auth
     *   └── timing
     *     └── logging
     * </pre>
     *
     * <p>A depth guard (maximum 100 layers) protects against corrupted or
     * accidentally cyclic chains. If the limit is reached, the output is
     * truncated with a warning message.</p>
     *
     * @return a multi-line string representation of the wrapper stack
     */
    default String toStringHierarchy() {
        // Maximum traversal depth — prevents infinite loops in corrupted chains
        int maxDepth = 100;
        StringBuilder sb = new StringBuilder();

        // Header line: chain ID for tracing context
        sb.append("Chain-ID: ")
                .append(chainId())
                .append("\n");

        // Walk the chain from outermost to innermost layer
        Wrapper<?> current = this;
        int depth = 0;
        while (current != null) {
            // Depth guard: truncate if the chain exceeds the maximum allowed depth
            if (depth >= maxDepth) {
                sb.repeat("  ", depth - 1).append("  └── ... (chain truncated at depth ")
                        .append(maxDepth).append(")\n");
                break;
            }

            // Indent inner layers with tree-drawing characters for visual clarity
            if (depth > 0) {
                sb.repeat("  ", depth - 1).append("  └── ");
            }

            // Append this layer's description and move to the next inner layer
            sb.append(current.layerDescription()).append("\n");
            current = current.inner();
            depth++;
        }
        return sb.toString();
    }
}
