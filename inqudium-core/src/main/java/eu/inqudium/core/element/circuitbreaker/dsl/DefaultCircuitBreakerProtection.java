package eu.inqudium.core.element.circuitbreaker.dsl;

import java.time.Duration;

class DefaultCircuitBreakerProtection implements CircuitBreakerProtection {

  private int failureThreshold = 50;
  private Duration waitDurationInOpenState = Duration.ofSeconds(60);
  private int permittedNumberOfCallsInHalfOpenState = 10;
  private FailureMetricsConfig metricsConfig;

  @Override
  public CircuitBreakerProtection trippingAtThreshold(int threshold) {
    this.failureThreshold = threshold;
    return this;
  }

  @Override
  public CircuitBreakerProtection waitingInOpenStateFor(Duration waitDuration) {
    this.waitDurationInOpenState = waitDuration;
    return this;
  }

  @Override
  public CircuitBreakerProtection permittingCallsInHalfOpen(int permittedCalls) {
    this.permittedNumberOfCallsInHalfOpenState = permittedCalls;
    return this;
  }

  @Override
  public CircuitBreakerProtection evaluatedBy(FailureMetricsConfig metricsConfig) {
    this.metricsConfig = metricsConfig;
    return this;
  }

  @Override
  public CircuitBreakerProtection evaluatedBy(FailureTrackingStrategy.Builder strategyBuilder) {
    this.metricsConfig = strategyBuilder.apply();
    return this;
  }

  @Override
  public CircuitBreakerConfig apply() {
    if (metricsConfig == null) {
      throw new IllegalStateException("A FailureMetricsConfig must be provided via evaluatedBy()");
    }
    return new CircuitBreakerConfig(
        failureThreshold,
        waitDurationInOpenState,
        permittedNumberOfCallsInHalfOpenState,
        metricsConfig
    );
  }
}
