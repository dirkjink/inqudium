package eu.inqudium.core.element.circuitbreaker.config;

/**
 * Configuration for the initial state of consecutive failure tracking.
 * Typically starts with 0 failures.
 */
public record ConsecutiveFailuresConfig(int initialConsecutiveFailures) {
}