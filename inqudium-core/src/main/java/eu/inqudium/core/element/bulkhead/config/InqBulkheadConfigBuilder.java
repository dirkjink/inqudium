package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;
import java.util.Objects;
import java.util.function.IntFunction;

public abstract class InqBulkheadConfigBuilder
    <B extends InqBulkheadConfigBuilder<B, E>, E extends ConfigExtension<E>>
    extends ExtensionBuilder<E> {

  private GeneralConfig generalConfig;
  private String name;
  private InqEventPublisher eventPublisher;
  private BulkheadStrategy strategy;
  private int maxConcurrentCalls;
  private Duration maxWaitDuration;
  private InqLimitAlgorithm limitAlgorithm;

  // Factory for creating a strategy that is coupled to maxConcurrentCalls.
  // When set, the strategy is lazily created during common() with the final
  // maxConcurrentCalls value, ensuring consistency.
  private IntFunction<BulkheadStrategy> strategyFactory;

  protected InqBulkheadConfigBuilder() {
  }

  @Override
  protected void general(GeneralConfig generalConfig) {
    this.generalConfig = generalConfig;
  }

  /**
   * Provides subclasses with read access to the general configuration.
   *
   * @return the general configuration, may be null if not yet injected
   */
  protected GeneralConfig getGeneralConfig() {
    return this.generalConfig;
  }

  protected abstract B self();

  // ──────────────────────────────────────────────────────────────────────────
  // Individual Setters — each guards its own value immediately
  // ──────────────────────────────────────────────────────────────────────────

  public B name(String name) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    this.name = name;
    return self();
  }

  /**
   * Sets the bulkhead strategy directly. Note: when using this method, the
   * strategy's internal concurrency limit is NOT automatically synchronized
   * with {@link #maxConcurrentCalls(int)}. Use {@link #strategyFactory(IntFunction)}
   * for automatic coupling.
   *
   * @param strategy the bulkhead strategy to use
   * @return this builder
   */
  public B strategy(BulkheadStrategy strategy) {
    Objects.requireNonNull(strategy, "strategy must not be null");
    this.strategy = strategy;
    this.strategyFactory = null; // explicit strategy overrides factory
    return self();
  }

  /**
   * Sets a factory that creates a {@link BulkheadStrategy} from the final
   * {@code maxConcurrentCalls} value. This ensures the strategy's internal
   * concurrency limit is always consistent with the builder's configuration.
   *
   * <p>Example usage:
   * <pre>{@code
   *   builder.strategyFactory(SemaphoreBulkheadStrategy::new)
   *          .maxConcurrentCalls(50)
   * }</pre>
   *
   * @param strategyFactory a function that creates a strategy from max concurrent calls
   * @return this builder
   */
  public B strategyFactory(IntFunction<BulkheadStrategy> strategyFactory) {
    Objects.requireNonNull(strategyFactory, "strategyFactory must not be null");
    this.strategyFactory = strategyFactory;
    this.strategy = null; // factory overrides explicit strategy
    return self();
  }

  public B maxWaitDuration(Duration maxWaitDuration) {
    Objects.requireNonNull(maxWaitDuration, "maxWaitDuration must not be null");
    if (maxWaitDuration.isNegative()) {
      throw new IllegalArgumentException(
          "maxWaitDuration must not be negative, got: " + maxWaitDuration);
    }
    this.maxWaitDuration = maxWaitDuration;
    return self();
  }

  public B maxConcurrentCalls(int maxConcurrentCalls) {
    if (maxConcurrentCalls <= 0) {
      throw new IllegalArgumentException(
          "maxConcurrentCalls must be positive, got: " + maxConcurrentCalls);
    }
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

  // ──────────────────────────────────────────────────────────────────────────
  // Build helper
  // ──────────────────────────────────────────────────────────────────────────

  protected InqBulkheadConfig common() {
    // Resolve strategy: factory takes precedence if set, ensuring coupling
    BulkheadStrategy resolvedStrategy = this.strategy;
    if (strategyFactory != null) {
      resolvedStrategy = strategyFactory.apply(maxConcurrentCalls);
    }

    InqElementCommonConfig common =
        new InqElementCommonConfig(name, InqElementType.BULKHEAD, eventPublisher);

    InqBulkheadConfig config = new InqBulkheadConfig(
        this.generalConfig,
        common.inference(),
        maxConcurrentCalls,
        resolvedStrategy,
        maxWaitDuration,
        limitAlgorithm
    ).inference();

    validate(config);
    return config;
  }

  /**
   * Validates cross-field constraints on the fully inferred configuration.
   * Individual field constraints are already enforced by each setter.
   */
  private void validate(InqBulkheadConfig config) {
    // Detect unconfigured fields — name is required and has no sensible default
    if (config.common() == null || config.common().name() == null) {
      throw new IllegalStateException(
          "name must be set. Call name(...) before building the bulkhead.");
    }

    if (config.maxConcurrentCalls() <= 0) {
      throw new IllegalStateException(
          "maxConcurrentCalls has not been set to a valid value.");
    }

    if (config.strategy() == null) {
      throw new IllegalStateException(
          "Either strategy(...) or strategyFactory(...) must be set "
              + "before building the bulkhead.");
    }
  }
}
