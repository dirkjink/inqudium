package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

public class InqBulkheadConfigBuilder extends ExtensionBuilder<InqBulkheadConfig> {
  private GeneralConfig generalConfig;
  private String name;
  private InqEventPublisher eventPublisher;
  private BulkheadStrategy strategy;
  private int maxConcurrentCalls;
  private Duration maxWaitDuration;
  private InqLimitAlgorithm limitAlgorithm;

  @Override
  protected void general(GeneralConfig generalConfig) {
    this.generalConfig = generalConfig;
  }

  public InqBulkheadConfigBuilder name(String name) {
    this.name = name;
    return this;
  }

  public InqBulkheadConfigBuilder strategy(BulkheadStrategy strategy) {
    this.strategy = strategy;
    return this;
  }

  public InqBulkheadConfigBuilder maxWaitDuration(Duration maxWaitDuration) {
    this.maxWaitDuration = maxWaitDuration;
    return this;
  }

  public InqBulkheadConfigBuilder maxConcurrentCalls(int maxConcurrentCalls) {
    this.maxConcurrentCalls = maxConcurrentCalls;
    return this;
  }

  public InqBulkheadConfigBuilder limitAlgorithm(InqLimitAlgorithm limitAlgorithm) {
    this.limitAlgorithm = limitAlgorithm;
    return this;
  }

  public InqBulkheadConfigBuilder eventPublisher(InqEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
    return this;
  }

  @Override
  public InqBulkheadConfig build() {
    if (eventPublisher == null) {
      eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
    }
    if (maxWaitDuration == null) {
      maxWaitDuration = Duration.ZERO;
    }
    return new InqBulkheadConfig(
        this.generalConfig,
        name,
        InqElementType.BULKHEAD,
        eventPublisher,
        maxConcurrentCalls,
        strategy,
        maxWaitDuration,
        limitAlgorithm
    );
  }
}
