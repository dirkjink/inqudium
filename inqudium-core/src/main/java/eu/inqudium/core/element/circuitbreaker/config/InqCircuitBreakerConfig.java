package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * Immutable, fully-resolved configuration for a circuit breaker element.
 *
 * <h2>Purpose</h2>
 * <p>This record aggregates all settings needed to construct and operate a circuit breaker
 * instance: general framework settings, element-common metadata (name, type, event publisher),
 * circuit-breaker-specific timing and threshold parameters, failure classification logic,
 * and the factory for the chosen {@link FailureMetrics} strategy.
 *
 * <h2>Configuration Hierarchy</h2>
 * <ul>
 *   <li>{@link GeneralConfig} — framework-wide defaults (e.g., global time source).</li>
 *   <li>{@link InqElementCommonConfig} — element-level metadata shared across all element
 *       types (name, element type, event publisher, exception optimization flag).</li>
 *   <li>Circuit-breaker-specific fields — half-open behavior, wait duration, failure
 *       predicate, and metrics factory.</li>
 * </ul>
 *
 * <h2>State Machine Parameters</h2>
 * <p>The circuit breaker operates a three-state machine (CLOSED → OPEN → HALF_OPEN → CLOSED):
 * <ul>
 *   <li>{@code waitDurationInOpenState} / {@code waitDurationNanos} — how long the breaker
 *       stays in the OPEN state before transitioning to HALF_OPEN for probing.</li>
 *   <li>{@code permittedCallsInHalfOpen} — how many trial calls are allowed through in
 *       the HALF_OPEN state.</li>
 *   <li>{@code successThresholdInHalfOpen} — how many of those trial calls must succeed
 *       for the breaker to close again.</li>
 * </ul>
 *
 * <h2>Failure Classification</h2>
 * <p>The {@code recordFailurePredicate} decides which exceptions count as failures.
 * Exceptions not matching the predicate are treated as successes from the circuit
 * breaker's perspective (they still propagate to the caller).
 *
 * <h2>Metrics Strategy</h2>
 * <p>The {@code metricsFactory} is a {@link LongFunction} that receives the current
 * timestamp in nanoseconds and returns an initial {@link FailureMetrics} instance.
 * This factory is invoked when the circuit breaker is created and on every reset
 * (transition to CLOSED).
 *
 * @param general                    framework-wide configuration; may be {@code null} if not injected
 * @param common                     element-level metadata (name, type, event publisher, exception optimization)
 * @param waitDurationNanos          the wait duration in the OPEN state, expressed in nanoseconds
 * @param successThresholdInHalfOpen the number of successes required in HALF_OPEN to close the circuit
 * @param permittedCallsInHalfOpen   the maximum number of trial calls permitted in the HALF_OPEN state
 * @param waitDurationInOpenState    the wait duration in the OPEN state as a {@link Duration}
 * @param recordFailurePredicate     predicate that classifies which exceptions are recorded as failures
 * @param metricsFactory             factory function producing the initial {@link FailureMetrics} instance
 *                                   given a nanosecond timestamp
 * @see InqCircuitBreakerConfigBuilder
 * @see FailureMetrics
 */
public record InqCircuitBreakerConfig(
        GeneralConfig general,
        InqElementCommonConfig common,
        long waitDurationNanos,
        int successThresholdInHalfOpen,
        int permittedCallsInHalfOpen,
        Duration waitDurationInOpenState,
        Predicate<Throwable> recordFailurePredicate,
        LongFunction<FailureMetrics> metricsFactory
) implements InqElementConfig, ConfigExtension<InqCircuitBreakerConfig> {

    /**
     * Delegates to the common config's name.
     */
    @Override
    public String name() {
        return common.name();
    }

    /**
     * Delegates to the common config's element type.
     */
    @Override
    public InqElementType elementType() {
        return common.elementType();
    }

    /**
     * Delegates to the common config's event publisher.
     */
    @Override
    public InqEventPublisher eventPublisher() {
        return common.eventPublisher();
    }

    /**
     * Delegates to the common config's exception optimization flag.
     */
    @Override
    public Boolean enableExceptionOptimization() {
        return common.enableExceptionOptimization();
    }

    @Override
    public InqCircuitBreakerConfig self() {
        return this;
    }
}
