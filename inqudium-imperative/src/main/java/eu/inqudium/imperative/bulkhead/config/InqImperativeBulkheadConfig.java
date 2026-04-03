package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.config.AimdLimitAlgorithmConfig;
import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.config.VegasLimitAlgorithmConfig;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;

import java.time.Duration;
import java.util.Optional;

public record InqImperativeBulkheadConfig(
    GeneralConfig general,
    InqBulkheadConfig bulkhead
) implements ConfigExtension<InqImperativeBulkheadConfig>, InqElementConfig {
  @Override
  public String name() {
    return bulkhead.name();
  }

  public BulkheadStrategy strategy() {
    return bulkhead.strategy();
  }

  public int maxConcurrentCalls() {
    return bulkhead.maxConcurrentCalls();
  }

  public Duration maxWaitDuration() {
    return bulkhead.maxWaitDuration();
  }

  public InqLimitAlgorithm limitAlgorithm() {
    return bulkhead.limitAlgorithm();
  }

  @Override
  public Boolean enableExceptionOptimization() {
    return bulkhead.enableExceptionOptimization();
  }

  @Override
  public InqElementType elementType() {
    return bulkhead.elementType();
  }

  @Override
  public InqEventPublisher eventPublisher() {
    return bulkhead.eventPublisher();
  }

  public BulkheadEventConfig eventConfig() {
    return bulkhead.eventConfig();
  }

  @Override
  public InqImperativeBulkheadConfig inference() {
    BulkheadStrategy strategy = strategy();
    if (strategy() == null) {
      Optional<VegasLimitAlgorithmConfig> vegas = general.of(VegasLimitAlgorithmConfig.class);
      Optional<AimdLimitAlgorithmConfig> aimd = general.of(AimdLimitAlgorithmConfig.class);

      if (vegas.filter(v -> aimd.isEmpty())
          .map(ConfigExtension.class::cast)
          .or(() ->
              aimd.filter(a -> vegas.isEmpty())
                  .map(ConfigExtension.class::cast)
          ).isEmpty()) {
        strategy = new SemaphoreBulkheadStrategy(maxConcurrentCalls());
      }
    }

    return new InqImperativeBulkheadConfig(
        this.general,
        new InqBulkheadConfig(
            this.general,
            this.bulkhead.common(),
            this.bulkhead.maxConcurrentCalls(),
            strategy,
            this.bulkhead.maxWaitDuration(),
            this.bulkhead.limitAlgorithm(),
            this.bulkhead.eventConfig()
        )
    );
  }

  @Override
  public InqImperativeBulkheadConfig self() {
    return this;
  }
}

