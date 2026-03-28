package eu.inqudium.core;

import eu.inqudium.core.compatibility.InqCompatibility;

/**
 * Base interface for all element configurations.
 *
 * <p>Configurations are immutable. Every config carries an {@link InqCompatibility}
 * reference that controls behavioral flags (ADR-013). Flags are resolved at
 * configuration build time — not on the hot path.
 *
 * @since 0.1.0
 */
public interface InqConfig {

  /**
   * Returns the compatibility flags for this configuration.
   *
   * <p>Used at configuration time to select algorithm variants based on
   * behavioral change flags (ADR-013).
   *
   * @return the compatibility instance
   */
  InqCompatibility getCompatibility();
}
