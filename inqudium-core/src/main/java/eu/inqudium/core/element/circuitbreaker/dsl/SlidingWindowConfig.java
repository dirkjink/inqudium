package eu.inqudium.core.element.circuitbreaker.dsl;

public record SlidingWindowConfig(
    int maxFailuresInWindow,
    int windowSize,
    int minimumNumberOfCalls
) implements FailureMetricsConfig {
}
