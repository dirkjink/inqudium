package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;

import java.time.Duration;

public class InqImperativeBulkheadConfigBuilder
    extends InqBulkheadConfigBuilder<InqImperativeBulkheadConfigBuilder,
    InqImperativeBulkheadConfig> {

  InqImperativeBulkheadConfigBuilder() {
  }

  public static InqImperativeBulkheadConfigBuilder bulkhead() {
    return standard();
  }

  public static InqImperativeBulkheadConfigBuilder standard() {
    return new InqImperativeBulkheadConfigBuilder()
        .maxConcurrentCalls(25)
        .maxWaitDuration(Duration.ZERO)
        // Use strategyFactory to ensure the strategy's permit count
        // is always consistent with the final maxConcurrentCalls value.
        // If the user later calls .maxConcurrentCalls(50), the strategy
        // will be created with 50 permits, not 25.
        .strategyFactory(SemaphoreBulkheadStrategy::new);
  }

  @Override
  public InqImperativeBulkheadConfig build() {
    return new InqImperativeBulkheadConfig(getGeneralConfig(), common()).inference();
  }

  @Override
  protected InqImperativeBulkheadConfigBuilder self() {
    return this;
  }
}
