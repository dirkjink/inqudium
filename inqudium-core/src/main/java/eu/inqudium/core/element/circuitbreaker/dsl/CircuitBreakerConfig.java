package eu.inqudium.core.element.circuitbreaker.dsl;

import eu.inqudium.core.config.InqConfig;

import java.time.Duration;
import java.util.List;

/**
 * The immutable configuration for a Circuit Breaker instance.
 */
public record CircuitBreakerConfig(
    String name,
    int failureThreshold,
    Duration waitDurationInOpenState,
    int permittedNumberOfCallsInHalfOpenState,
    List<Class<? extends Throwable>> penalizedExceptions,
    List<Class<? extends Throwable>> toleratedExceptions,
    FailureMetricsConfig metricsConfig,
    InqConfig inqConfig
) {}