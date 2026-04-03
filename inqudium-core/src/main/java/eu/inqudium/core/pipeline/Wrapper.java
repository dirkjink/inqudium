package eu.inqudium.core.pipeline;

public interface Wrapper<S extends Wrapper<S>> {
  S getInner();
  String getChainId();
  String getLayerDescription();

  /**
   * Renders the wrapper hierarchy from this layer inward.
   * Since there is no outer reference, the caller determines the starting point.
   * Typically called on the outermost wrapper known to the caller.
   */
  default String toStringHierarchy() {
    StringBuilder sb = new StringBuilder();
    sb.append("Chain-ID: ").append(getChainId()).append("\n");

    Wrapper<?> current = this;
    int depth = 0;
    while (current != null) {
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
