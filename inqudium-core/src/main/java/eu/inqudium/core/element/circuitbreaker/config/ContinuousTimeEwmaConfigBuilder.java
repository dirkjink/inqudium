package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.time.Duration;

public class ContinuousTimeEwmaConfigBuilder extends ExtensionBuilder<ContinuousTimeEwmaConfig> {
  private Duration timeConstant;
  private Integer minimumNumberOfCalls;

  public ContinuousTimeEwmaConfigBuilder timeConstant(Duration tau) {
    this.timeConstant = tau;
    return this;
  }

  public ContinuousTimeEwmaConfigBuilder minimumNumberOfCalls(int calls) {
    this.minimumNumberOfCalls = calls;
    return this;
  }

  /**
   * Short time constant (Tau) makes the rate decay or increase very rapidly.
   */
  public ContinuousTimeEwmaConfigBuilder protective() {
    this.timeConstant = Duration.ofSeconds(5);
    this.minimumNumberOfCalls = 5;
    return this;
  }

  /**
   * Medium time constant suitable for typical cloud service latencies and patterns.
   */
  public ContinuousTimeEwmaConfigBuilder balanced() {
    this.timeConstant = Duration.ofSeconds(30);
    this.minimumNumberOfCalls = 10;
    return this;
  }

  /**
   * Long time constant that provides a very stable, long-term failure average.
   */
  public ContinuousTimeEwmaConfigBuilder permissive() {
    this.timeConstant = Duration.ofSeconds(5);
    this.minimumNumberOfCalls = 50;
    return this;
  }

  @Override
  public ContinuousTimeEwmaConfig build() {
    if (timeConstant == null || minimumNumberOfCalls == null) {
      balanced();
    }
    return new ContinuousTimeEwmaConfig(timeConstant, minimumNumberOfCalls);
  }
}