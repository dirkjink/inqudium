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
 * @param <S> the concrete self-type of the wrapper (recursive generic bound)
 */
public interface Wrapper<S extends Wrapper<S>> {

  /**
   * Returns the next inner layer, or {@code null} at the end of the chain.
   */
  S getInner();

  /**
   * Returns the unique chain identifier shared by all layers wrapping the same core delegate.
   * Uses a primitive {@code long} counter for zero-allocation identification.
   */
  long getChainId();

  /**
   * Returns a human-readable description of this layer.
   */
  String getLayerDescription();

  /**
   * Renders the wrapper hierarchy as a formatted tree string, starting from this layer inward.
   * A depth guard (max 100 layers) protects against corrupted chains.
   */
  default String toStringHierarchy() {
    int maxDepth = 100;
    StringBuilder sb = new StringBuilder();
    sb.append("Chain-ID: ").append(getChainId()).append("\n");

    Wrapper<?> current = this;
    int depth = 0;
    while (current != null) {
      if (depth >= maxDepth) {
        sb.append("  ".repeat(depth - 1)).append("  └── ... (chain truncated at depth ")
          .append(maxDepth).append(")\n");
        break;
      }
      if (depth > 0) {
        sb.append("  ".repeat(depth - 1)).append("  └── ");
      }
      sb.append(current.getLayerDescription()).append("\n");
      current = current.getInner();
      depth++;
    }
    return sb.toString();
  }
}
