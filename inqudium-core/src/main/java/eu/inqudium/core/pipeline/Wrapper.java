package eu.inqudium.core.pipeline;

public interface Wrapper<S extends Wrapper<S>> {
  S getInner();
  S getOuter();
  void setOuter(S outer);
  String getChainId();
  String getLayerDescription();

  @SuppressWarnings("unchecked")
  default S getOutermost() {
    S current = (S) this;
    while (current.getOuter() != null) {
      current = current.getOuter();
    }
    return current;
  }

  default String toStringHierarchy() {
    StringBuilder sb = new StringBuilder();
    Wrapper<?> root = getOutermost();
    sb.append("Chain-ID: ").append(root.getChainId()).append("\n");

    Wrapper<?> current = root;
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