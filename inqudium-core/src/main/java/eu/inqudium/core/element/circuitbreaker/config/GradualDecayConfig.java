package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.metrics.GradualDecayMetrics;

/**
 * Configuration for gradual failure decay where one success offsets one failure.
 */
public record GradualDecayConfig(int initialFailureCount) {
  // Starts with a failure count of zero.
}
