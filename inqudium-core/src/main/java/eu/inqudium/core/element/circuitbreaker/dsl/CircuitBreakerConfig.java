package eu.inqudium.core.element.circuitbreaker.dsl;

import eu.inqudium.core.config.InqConfig;

import java.time.Duration;
import java.util.List;

public record CircuitBreakerConfig(
    int failureThreshold,
    Duration waitDurationInOpenState,
    int permittedNumberOfCallsInHalfOpenState,
    FailureMetricsConfig metricsConfig,
    List<Class<? extends Throwable>> recordedExceptions, // NEU
    List<Class<? extends Throwable>> ignoredExceptions,  // NEU
    InqConfig inqConfig
) {
}
