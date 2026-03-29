package eu.inqudium.core.cache;

import eu.inqudium.core.InqConfig;
import eu.inqudium.core.compatibility.InqCompatibility;

/**
 * Configuration placeholder for the Cache element.
 *
 * <p>The cache behavioral contract and implementations will be specified
 * in a future ADR when the cache element enters active development (Phase 2).
 *
 * @since 0.1.0
 */
public final class InqCacheConfig implements InqConfig {

  private static final InqCacheConfig DEFAULTS = new InqCacheConfig(InqCompatibility.ofDefaults());

  private final InqCompatibility compatibility;

  private InqCacheConfig(InqCompatibility compatibility) {
    this.compatibility = compatibility;
  }

  public static InqCacheConfig ofDefaults() {
    return DEFAULTS;
  }

  @Override
  public InqCompatibility getCompatibility() {
    return compatibility;
  }
}
