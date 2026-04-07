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
  @Override
  public String name() {
    return common.name();
  }

  @Override
  public InqElementType elementType() {
    return common.elementType();
  }

  @Override
  public InqEventPublisher eventPublisher() {
    return common.eventPublisher();
  }

  @Override
  public Boolean enableExceptionOptimization() {
    return common.enableExceptionOptimization();
  }

  @Override
  public InqCircuitBreakerConfig self() {
    return this;
  }
}

