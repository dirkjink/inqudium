package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

public record InqCircuitBreakerConfig(
    GeneralConfig general,
    InqElementCommonConfig common,
    Double failureRateThreshold,
    Double slowCallRateThreshold,
    FailureMetricsConfig failureMetrics,
    Integer slidingWindowSize,
    Integer waitDurationInOpenState,
    Integer permittedNumberOfCallsInHalfOpenState

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

  @Override
  public InqCircuitBreakerConfig inference() {
    return new InqCircuitBreakerConfig(
        this.general,
        this.common,
        this.failureRateThreshold,
        this.slowCallRateThreshold,
        this.failureMetrics,
        this.slidingWindowSize,
        this.waitDurationInOpenState,
        this.permittedNumberOfCallsInHalfOpenState
    );
  }
}

