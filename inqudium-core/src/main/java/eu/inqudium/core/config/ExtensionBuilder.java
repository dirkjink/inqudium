package eu.inqudium.core.config;

/**
 * Common interface for all dynamic extension builders.
 *
 * @param <E> The type of the extension being built.
 */
public abstract class ExtensionBuilder<E extends ConfigExtension<E>> {
  protected void general(GeneralConfig generalConfig) {
  }

  public abstract E build();
}
