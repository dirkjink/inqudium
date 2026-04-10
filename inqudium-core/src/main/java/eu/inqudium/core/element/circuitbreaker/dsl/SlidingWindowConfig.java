package eu.inqudium.core.element.circuitbreaker.dsl;

public record SlidingWindowConfig(
        int windowSize,
        int minimumNumberOfCalls
) implements FailureMetricsConfig {
}
