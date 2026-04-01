package eu.inqudium.core.element.bulkhead;

import eu.inqudium.core.callid.InqCallIdGenerator;
import eu.inqudium.core.config.InqConfigOld;
import eu.inqudium.core.config.compatibility.InqCompatibility;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.NonBlockingBulkheadStrategy;
import eu.inqudium.core.time.InqClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Immutable configuration for the Bulkhead element.
 *
 * <p>Carries a pluggable {@link BulkheadStrategy} that defines how concurrent
 * access is controlled.
 *
 * <p>A strategy can also be set explicitly via {@link Builder#strategy(BulkheadStrategy)},
 * which accepts any {@link BulkheadStrategy} subtype (blocking or non-blocking).
 *
 * @since 0.3.0
 */
public final class BulkheadConfig implements InqConfigOld {
  private static final BulkheadConfig DEFAULTS = BulkheadConfig.builder().build();

  private final int maxConcurrentCalls;
  private final Duration maxWaitDuration;
  private final InqCompatibility compatibility;
  private final InqClock clock;
  private final Logger logger;
  private final InqCallIdGenerator callIdGenerator;
  private final LongSupplier nanoTimeSource;
  private final BulkheadStrategy strategy;

  // Retained for introspection
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
   * Returns the strategy resolved during {@link Builder#build()}.
   *
   * <p>For imperative usage, this is always a {@link BlockingBulkheadStrategy}
   * (unless an explicit non-blocking strategy was set). Use
   * {@link #getBlockingStrategy()} for a type-safe cast.
   */
  public BulkheadStrategy getStrategy() {
    return strategy;
  }

  /**
   * Returns the strategy as a {@link BlockingBulkheadStrategy}.
   *
   * @throws IllegalStateException if the strategy is not a blocking strategy
   */
  public BlockingBulkheadStrategy getBlockingStrategy() {
    if (strategy instanceof BlockingBulkheadStrategy blocking) {
      return blocking;
    }
    throw new IllegalStateException(
        "Expected a BlockingBulkheadStrategy but got " + strategy.getClass().getName()
            + ". Use getStrategy() for non-blocking strategies or configure a blocking strategy.");
  }

  /**
   * Returns the strategy as a {@link NonBlockingBulkheadStrategy}.
   *
   * @throws IllegalStateException if the strategy is not a non-blocking strategy
   */
  public NonBlockingBulkheadStrategy getNonBlockingStrategy() {
    if (strategy instanceof NonBlockingBulkheadStrategy nonBlocking) {
      return nonBlocking;
    }
    throw new IllegalStateException(
        "Expected a NonBlockingBulkheadStrategy but got " + strategy.getClass().getName()
            + ". Use getStrategy() for blocking strategies or configure a non-blocking strategy.");
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
    private InqClock clock = InqConfigOld.defaultClock();
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
      if (max < 0) throw new IllegalArgumentException("maxConcurrentCalls must be >= 0, but was: " + max);
      this.maxConcurrentCalls = max;
      return this;
    }

    public Builder maxWaitDuration(Duration duration) {
      Objects.requireNonNull(duration, "maxWaitDuration must not be null");
      if (duration.isNegative()) throw new IllegalArgumentException("maxWaitDuration must not be negative");
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

    public Builder limitAlgorithm(InqLimitAlgorithm algo) {
      this.limitAlgorithm = algo;
      return this;
    }

    public Builder nanoTimeSource(LongSupplier s) {
      this.nanoTimeSource = Objects.requireNonNull(s);
      return this;
    }

    public Builder codel(Duration targetDelay, Duration interval) {
      Objects.requireNonNull(targetDelay, "CoDel targetDelay must not be null");
      Objects.requireNonNull(interval, "CoDel interval must not be null");
      if (targetDelay.isNegative() || targetDelay.isZero())
        throw new IllegalArgumentException("CoDel targetDelay must be positive");
      if (interval.isNegative() || interval.isZero())
        throw new IllegalArgumentException("CoDel interval must be positive");
      this.codelTargetDelay = targetDelay;
      this.codelInterval = interval;
      return this;
    }

    /**
     * Sets an explicit strategy, overriding the automatic selection.
     * Accepts any {@link BulkheadStrategy} subtype — blocking or non-blocking.
     */
    public Builder strategy(BulkheadStrategy strategy) {
      this.explicitStrategy = Objects.requireNonNull(strategy);
      return this;
    }

    public BulkheadConfig build() {
      return new BulkheadConfig(this, explicitStrategy);
    }
  }
}
