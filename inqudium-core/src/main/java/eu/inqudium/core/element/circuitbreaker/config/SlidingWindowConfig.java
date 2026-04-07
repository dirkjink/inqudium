package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Configuration for count-based sliding window metrics.
 */
public record SlidingWindowConfig(int windowSize, int minimumNumberOfCalls)
    implements ConfigExtension<SlidingWindowConfig> {
  @Override
  public SlidingWindowConfig self() {
    return this;
  }
}