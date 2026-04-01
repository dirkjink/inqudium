package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqConfig;
import eu.inqudium.core.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.bulkhead.strategy.AdaptiveBulkheadStrategy;
import eu.inqudium.core.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.bulkhead.strategy.CoDelBulkheadStrategy;
import eu.inqudium.core.bulkhead.strategy.SemaphoreBulkheadStrategy;
import eu.inqudium.core.compatibility.InqCompatibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Immutable configuration for the Bulkhead element.
 *
 * <p>Carries a pluggable {@link BulkheadStrategy} that defines how concurrent
 * access is controlled. The builder automatically selects the appropriate strategy
 * based on the configured options:
 * <ul>
 *   <li>CoDel parameters → {@link CoDelBulkheadStrategy}</li>
 *   <li>Limit algorithm → {@link AdaptiveBulkheadStrategy}</li>
 *   <li>Neither → {@link SemaphoreBulkheadStrategy} (default)</li>
 * </ul>
 *
 * <p>Alternatively, a strategy can be set explicitly via
 * {@link Builder#strategy(BulkheadStrategy)}, which overrides the automatic selection.
 *
 * @since 0.3.0
 */
public final class BulkheadConfig implements InqConfig {
  private static final BulkheadConfig DEFAULTS = BulkheadConfig.builder().build();

  private final int maxConcurrentCalls;
  private final Duration maxWaitDuration;
  private final InqCompatibility compatibility;
  private final InqClock clock;
  private final Logger logger;
  private final InqCallIdGenerator callIdGenerator;
  private final LongSupplier nanoTimeSource;
  private final BulkheadStrategy strategy;

  // Retained for introspection (what was configured, not what strategy was selected)
  private final InqLimitAlgorithm limitAlgorithm;
  private final Duration codelTargetDelay;
  private final Duration codelInterval;

  private BulkheadConfig(Builder b, BulkheadStrategy strategy) {
    this.maxConcurrentCalls = b.maxConcurrentCalls;
    this.maxWaitDuration = b.maxWaitDuration;
    this.compatibility = b.compatibility;
    this.clock = b.clock;
    this.logger = b.logger;
    this.callIdGenerator = b.callIdGenerator;
    this.nanoTimeSource = b.nanoTimeSource;
    this.limitAlgorithm = b.limitAlgorithm;
    this.codelTargetDelay = b.codelTargetDelay;
    this.codelInterval = b.codelInterval;
    this.strategy = strategy;
  }

  public static BulkheadConfig ofDefaults() {
    return DEFAULTS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int getMaxConcurrentCalls() {
    return maxConcurrentCalls;
  }

  public Duration getMaxWaitDuration() {
    return maxWaitDuration;
  }

  @Override
  public InqCompatibility getCompatibility() {
    return compatibility;
  }

  @Override
  public InqClock getClock() {
    return clock;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }

  @Override
  public InqCallIdGenerator getCallIdGenerator() {
    return callIdGenerator;
  }

  public InqLimitAlgorithm getLimitAlgorithm() {
    return limitAlgorithm;
  }

  public LongSupplier getNanoTimeSource() {
    return nanoTimeSource;
  }

  public Duration getCodelTargetDelay() {
    return codelTargetDelay;
  }

  public Duration getCodelInterval() {
    return codelInterval;
  }

  public boolean isCoDelEnabled() {
    return codelTargetDelay != null && codelInterval != null;
  }

  /**
   * Returns the bulkhead strategy that was automatically selected or explicitly set.
   */
  public BulkheadStrategy getStrategy() {
    return strategy;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    BulkheadConfig that = (BulkheadConfig) o;
    return maxConcurrentCalls == that.maxConcurrentCalls
        && Objects.equals(maxWaitDuration, that.maxWaitDuration)
        && Objects.equals(compatibility, that.compatibility)
        && Objects.equals(limitAlgorithm, that.limitAlgorithm)
        && Objects.equals(codelTargetDelay, that.codelTargetDelay)
        && Objects.equals(codelInterval, that.codelInterval);
  }

  @Override
  public int hashCode() {
    return Objects.hash(maxConcurrentCalls, maxWaitDuration, compatibility,
        limitAlgorithm, codelTargetDelay, codelInterval);
  }

  public static final class Builder {
    private int maxConcurrentCalls = 25;
    private Duration maxWaitDuration = Duration.ZERO;
    private InqCompatibility compatibility = InqCompatibility.ofDefaults();
    private InqClock clock = InqConfig.defaultClock();
    private Logger logger = LoggerFactory.getLogger(BulkheadConfig.class);
    private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();
    private InqLimitAlgorithm limitAlgorithm = null;
    private LongSupplier nanoTimeSource = System::nanoTime;
    private Duration codelTargetDelay = null;
    private Duration codelInterval = null;
    private BulkheadStrategy explicitStrategy = null;

    private Builder() {
    }

    public Builder maxConcurrentCalls(int max) {
      if (max < 0) {
        throw new IllegalArgumentException("maxConcurrentCalls must be >= 0, but was: " + max);
      }
      this.maxConcurrentCalls = max;
      return this;
    }

    public Builder maxWaitDuration(Duration duration) {
      Objects.requireNonNull(duration, "maxWaitDuration must not be null");
      if (duration.isNegative()) {
        throw new IllegalArgumentException("maxWaitDuration must not be negative, but was: " + duration);
      }
      try {
        duration.toNanos();
        this.maxWaitDuration = duration;
      } catch (ArithmeticException e) {
        this.maxWaitDuration = Duration.ofNanos(Long.MAX_VALUE);
        logger.warn("Extremely large wait duration — clamped to {} days.", maxWaitDuration.toDays());
      }
      return this;
    }

    public Builder compatibility(InqCompatibility c) {
      this.compatibility = Objects.requireNonNull(c);
      return this;
    }

    public Builder clock(InqClock clock) {
      this.clock = Objects.requireNonNull(clock);
      return this;
    }

    public Builder logger(Logger logger) {
      this.logger = Objects.requireNonNull(logger);
      return this;
    }

    public Builder callIdGenerator(InqCallIdGenerator gen) {
      this.callIdGenerator = Objects.requireNonNull(gen);
      return this;
    }

    public Builder limitAlgorithm(InqLimitAlgorithm limitAlgorithm) {
      this.limitAlgorithm = limitAlgorithm;
      return this;
    }

    public Builder nanoTimeSource(LongSupplier nanoTimeSource) {
      this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
      return this;
    }

    public Builder codel(Duration targetDelay, Duration interval) {
      Objects.requireNonNull(targetDelay, "CoDel targetDelay must not be null");
      Objects.requireNonNull(interval, "CoDel interval must not be null");
      if (targetDelay.isNegative() || targetDelay.isZero()) {
        throw new IllegalArgumentException("CoDel targetDelay must be positive, but was: " + targetDelay);
      }
      if (interval.isNegative() || interval.isZero()) {
        throw new IllegalArgumentException("CoDel interval must be positive, but was: " + interval);
      }
      this.codelTargetDelay = targetDelay;
      this.codelInterval = interval;
      return this;
    }

    /**
     * Sets an explicit strategy, overriding the automatic selection.
     *
     * <p>When set, the builder ignores {@link #limitAlgorithm} and {@link #codel}
     * for strategy selection (but they are still available via getters for introspection).
     */
    public Builder strategy(BulkheadStrategy strategy) {
      this.explicitStrategy = Objects.requireNonNull(strategy, "strategy must not be null");
      return this;
    }

    /**
     * Builds the immutable configuration.
     *
     * <p>Strategy selection order:
     * <ol>
     *   <li>Explicit strategy (via {@link #strategy}) — used as-is</li>
     *   <li>CoDel parameters → {@link CoDelBulkheadStrategy}</li>
     *   <li>Limit algorithm → {@link AdaptiveBulkheadStrategy}</li>
     *   <li>Default → {@link SemaphoreBulkheadStrategy}</li>
     * </ol>
     *
     * @throws IllegalStateException if limitAlgorithm and CoDel are both set
     *                               (without an explicit strategy to override)
     */
    public BulkheadConfig build() {
      BulkheadStrategy resolvedStrategy;

      if (explicitStrategy != null) {
        resolvedStrategy = explicitStrategy;
      } else {
        // Validate mutual exclusivity
        if (limitAlgorithm != null && codelTargetDelay != null) {
          throw new IllegalStateException(
              "Cannot combine a limitAlgorithm with CoDel configuration. "
                  + "Use either limitAlgorithm() or codel(), but not both. "
                  + "Or set an explicit strategy() to override.");
        }

        if (codelTargetDelay != null && codelInterval != null) {
          resolvedStrategy = new CoDelBulkheadStrategy(
              maxConcurrentCalls, codelTargetDelay, codelInterval, nanoTimeSource);
        } else if (limitAlgorithm != null) {
          resolvedStrategy = new AdaptiveBulkheadStrategy(limitAlgorithm);
        } else {
          resolvedStrategy = new SemaphoreBulkheadStrategy(maxConcurrentCalls);
        }
      }

      return new BulkheadConfig(this, resolvedStrategy);
    }
  }
}
