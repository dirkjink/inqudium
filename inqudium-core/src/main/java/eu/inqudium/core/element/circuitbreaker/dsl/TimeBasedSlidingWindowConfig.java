package eu.inqudium.core.element.circuitbreaker.dsl;

public record TimeBasedSlidingWindowConfig(
    int maxFailuresInWindow,
    int windowSizeInSeconds
) implements FailureMetricsConfig {
}
