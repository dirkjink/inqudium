package eu.inqudium.core.element.circuitbreaker.dsl;

import java.time.Duration;

public interface CircuitBreakerProtection {

  CircuitBreakerProtection trippingAtThreshold(int threshold);

  CircuitBreakerProtection waitingInOpenStateFor(Duration waitDuration);

  CircuitBreakerProtection permittingCallsInHalfOpen(int permittedCalls);

  @SuppressWarnings("unchecked")
  CircuitBreakerProtection failingOn(Class<? extends Throwable>... exceptions);

  @SuppressWarnings("unchecked")
  CircuitBreakerProtection ignoringOn(Class<? extends Throwable>... exceptions);


  CircuitBreakerProtection evaluatedBy(FailureMetricsConfig metricsConfig);

  CircuitBreakerProtection evaluatedBy(FailureTrackingStrategy.Builder strategyBuilder);

  CircuitBreakerConfig apply();
}
