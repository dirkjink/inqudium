package eu.inqudium.core.element.circuitbreaker.dsl;

public record TimeBasedSlidingWindowConfig(
    int windowSizeInSeconds
) implements FailureMetricsConfig {
}
