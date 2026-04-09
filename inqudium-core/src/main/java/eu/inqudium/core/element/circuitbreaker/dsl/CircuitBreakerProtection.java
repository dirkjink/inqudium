package eu.inqudium.core.element.circuitbreaker.dsl;

import java.time.Duration;

public interface CircuitBreakerProtection {

  CircuitBreakerProtection trippingAtThreshold(int threshold);

  CircuitBreakerProtection waitingInOpenStateFor(Duration waitDuration);

  CircuitBreakerProtection permittingCallsInHalfOpen(int permittedCalls);

  CircuitBreakerProtection evaluatedBy(FailureMetricsConfig metricsConfig);

  CircuitBreakerProtection evaluatedBy(FailureTrackingStrategy.Builder strategyBuilder);

  CircuitBreakerConfig apply();
}
