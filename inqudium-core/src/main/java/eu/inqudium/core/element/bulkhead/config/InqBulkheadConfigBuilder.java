package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

public abstract class InqBulkheadConfigBuilder
    <B extends InqBulkheadConfigBuilder<B, E>, E extends ConfigExtension>
    extends ExtensionBuilder<E> {
  protected GeneralConfig generalConfig;
  private String name;
  private InqEventPublisher eventPublisher;
  private BulkheadStrategy strategy;
  private int maxConcurrentCalls;
  private Duration maxWaitDuration;
  private InqLimitAlgorithm limitAlgorithm;

  protected InqBulkheadConfigBuilder() {}
  
  @Override
  protected void general(GeneralConfig generalConfig) {
    this.generalConfig = generalConfig;
  }
  
  protected abstract B self();

  public B name(String name) {
    this.name = name;
    return self();
  }

  public B strategy(BulkheadStrategy strategy) {
    this.strategy = strategy;
    return self();
  }

  public B maxWaitDuration(Duration maxWaitDuration) {
    this.maxWaitDuration = maxWaitDuration;
    return self();
  }

  public B maxConcurrentCalls(int maxConcurrentCalls) {
    this.maxConcurrentCalls = maxConcurrentCalls;
    return self();
  }

  public B limitAlgorithm(InqLimitAlgorithm limitAlgorithm) {
    this.limitAlgorithm = limitAlgorithm;
    return self();
  }

  public B eventPublisher(InqEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
    return self();
  }

  protected InqBulkheadConfig common() {
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
