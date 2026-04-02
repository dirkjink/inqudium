package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfigBuilder;

public class InqImperativeBulkheadConfigBuilder extends InqBulkheadConfigBuilder<InqImperativeBulkheadConfigBuilder, InqImperativeBulkheadConfig> {

  InqImperativeBulkheadConfigBuilder() {
  }

  public static InqImperativeBulkheadConfigBuilder bulkhead() {
    return new InqImperativeBulkheadConfigBuilder();
  }

  @Override
  public InqImperativeBulkheadConfig build() {
    return new InqImperativeBulkheadConfig(generalConfig, common());
  }

  @Override
  protected InqImperativeBulkheadConfigBuilder self() {
    return this;
  }
}
