package eu.inqudium.core.element.circuitbreaker.dsl;

import java.time.Duration;

public record CircuitBreakerConfig(
    int failureThreshold,
    Duration waitDurationInOpenState,
    int permittedNumberOfCallsInHalfOpenState,
    FailureMetricsConfig metricsConfig
) {
}
