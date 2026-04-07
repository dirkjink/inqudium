package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Configuration for absolute count-based sliding window metrics over time.
 */
public record TimeBasedSlidingWindowConfig(
    int maxFailuresInWindow,
    int windowSizeInSeconds)
    implements ConfigExtension<TimeBasedSlidingWindowConfig> {

  @Override
  public TimeBasedSlidingWindowConfig self() {
    return this;
  }
}
