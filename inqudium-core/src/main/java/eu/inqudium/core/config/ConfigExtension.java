package eu.inqudium.core.config;

public interface ConfigExtension<C extends ConfigExtension<C>> {
  default C inference() {
    return self();
  }

  C self();
}
