package eu.inqudium.core.element.circuitbreaker.dsl;

public interface CircuitBreakerCountEvaluation {
  CircuitBreakerCountEvaluation keepingHistoryOf(int numberOfCalls);
  CircuitBreakerCountEvaluation requiringAtLeast(int minimumCalls);

  CircuitBreakerConfig applyProtectiveProfile();
  CircuitBreakerConfig applyBalancedProfile();
  CircuitBreakerConfig applyPermissiveProfile();
  CircuitBreakerConfig apply();
}