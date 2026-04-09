package eu.inqudium.core.element.circuitbreaker.dsl;

public sealed interface FailureMetricsConfig permits
    SlidingWindowConfig,
    TimeBasedSlidingWindowConfig {
}

