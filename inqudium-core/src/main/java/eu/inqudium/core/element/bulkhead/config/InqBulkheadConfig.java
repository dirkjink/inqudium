package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

public record InqBulkheadConfig(
    GeneralConfig general,
    InqElementCommonConfig common,
    int maxConcurrentCalls,
    BulkheadStrategy strategy,
    Duration maxWaitDuration,
    InqLimitAlgorithm limitAlgorithm,
    BulkheadEventConfig eventConfig
) implements InqElementConfig, ConfigExtension<InqBulkheadConfig> {
  @Override
  public String name() {
    return common.name();
  }

  @Override
  public InqElementType elementType() {
    return common.elementType();
  }

  @Override
  public InqEventPublisher eventPublisher() {
    return common.eventPublisher();
  }

  @Override
  public Boolean enableExceptionOptimization() {
    return common.enableExceptionOptimization();
  }

  @Override
  public InqBulkheadConfig self() {
    return this;
  }

  @Override
  public InqBulkheadConfig inference() {
    BulkheadEventConfig eventConfigInference = eventConfig;
    if (eventConfig == null) {
      eventConfigInference = BulkheadEventConfig.standard();
    }
    return new InqBulkheadConfig(
        this.general,
        this.common,
        this.maxConcurrentCalls,
        this.strategy,
        this.maxWaitDuration,
        this.limitAlgorithm,
        eventConfigInference
    );
  }
}

