package eu.inqudium.imperative.circuitbreaker.config;

import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfigBuilder;

public class InqImperativeCircuitBreakerConfigBuilder
    extends InqCircuitBreakerConfigBuilder<InqImperativeCircuitBreakerConfigBuilder, InqImperativeCircuitBreakerConfig> {

  public static InqImperativeCircuitBreakerConfigBuilder circuitBreaker() {
    return standard();
  }

  public static InqImperativeCircuitBreakerConfigBuilder standard() {
    return new InqImperativeCircuitBreakerConfigBuilder();
  }

  @Override
  protected InqImperativeCircuitBreakerConfigBuilder self() {
    return this;
  }

  @Override
  public InqImperativeCircuitBreakerConfig build() {
    return null;
  }
}
