package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqConfig;
import eu.inqudium.core.compatibility.InqCompatibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for the Bulkhead element (ADR-020).
 *
 * @since 0.1.0
 */
public final class BulkheadConfig implements InqConfig {
  private static final BulkheadConfig DEFAULTS = BulkheadConfig.builder().build();

  private final int maxConcurrentCalls;
  private final Duration maxWaitDuration;
  private final InqCompatibility compatibility;
  private final InqClock clock;
  private final Logger logger;
  private final InqCallIdGenerator callIdGenerator;

  // New property for adaptive concurrency limits
  private final InqLimitAlgorithm limitAlgorithm;

  private BulkheadConfig(Builder b) {
    this.maxConcurrentCalls = b.maxConcurrentCalls;
    this.maxWaitDuration = b.maxWaitDuration;
    this.compatibility = b.compatibility;
    this.clock = b.clock;
    this.logger = b.logger;
    this.callIdGenerator = b.callIdGenerator;
    this.limitAlgorithm = b.limitAlgorithm;
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

  /**
   * Returns the algorithm used for adaptive concurrency limits.
   * If null, the bulkhead uses a static limit based on {@link #getMaxConcurrentCalls()}.
   *
   * @return the limit algorithm or null
   */
  public InqLimitAlgorithm getLimitAlgorithm() {
    return limitAlgorithm;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    BulkheadConfig that = (BulkheadConfig) o;
    return maxConcurrentCalls == that.maxConcurrentCalls &&
        Objects.equals(maxWaitDuration, that.maxWaitDuration) &&
        Objects.equals(compatibility, that.compatibility) &&
        Objects.equals(limitAlgorithm, that.limitAlgorithm);
  }

  @Override
  public int hashCode() {
    return Objects.hash(maxConcurrentCalls, maxWaitDuration, compatibility, limitAlgorithm);
  }

  public static final class Builder {
    private int maxConcurrentCalls = 25;
    private Duration maxWaitDuration = Duration.ZERO;
    private InqCompatibility compatibility = InqCompatibility.ofDefaults();
    private InqClock clock = InqConfig.defaultClock();
    private Logger logger = LoggerFactory.getLogger(BulkheadConfig.class);
    private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();
    private InqLimitAlgorithm limitAlgorithm = null; // Default is static (no adaptive algorithm)

    private Builder() {
    }

    public Builder maxConcurrentCalls(int max) {
      this.maxConcurrentCalls = max;
      return this;
    }

    public Builder maxWaitDuration(Duration duration) {
      Objects.requireNonNull(duration);
      try {
        duration.toNanos();
        this.maxWaitDuration = duration;
      } catch (ArithmeticException e) {
        this.maxWaitDuration = Duration.ofNanos(Long.MAX_VALUE);
        logger.warn("Bulkhead configuration with extremely large wait duration. " +
                "Will safely fall back to {} DAYS during permit acquisition to prevent arithmetic overflow.",
            maxWaitDuration.toDays());
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

    /**
     * Sets the adaptive limit algorithm (e.g., AIMD or Vegas).
     * If set, the bulkhead will dynamically adjust its concurrency limits.
     *
     * @param limitAlgorithm the algorithm to use, or null for static limits
     * @return the builder instance
     */
    public Builder limitAlgorithm(InqLimitAlgorithm limitAlgorithm) {
      // Null is explicitly allowed here to disable adaptive limits and revert to static
      this.limitAlgorithm = limitAlgorithm;
      return this;
    }

    public BulkheadConfig build() {
      return new BulkheadConfig(this);
    }
  }
}