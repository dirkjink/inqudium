package eu.inqudium.core.element.circuitbreaker.dsl;

public interface CircuitBreakerTimeEvaluation {
    CircuitBreakerTimeEvaluation lookingAtTheLast(int seconds);

    CircuitBreakerConfig applyProtectiveProfile();

    CircuitBreakerConfig applyBalancedProfile();

    CircuitBreakerConfig applyPermissiveProfile();

    CircuitBreakerConfig apply();
}
