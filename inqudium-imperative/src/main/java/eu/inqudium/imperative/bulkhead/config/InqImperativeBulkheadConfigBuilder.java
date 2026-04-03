package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;

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
        .eventConfig(BulkheadEventConfig.standard());
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
