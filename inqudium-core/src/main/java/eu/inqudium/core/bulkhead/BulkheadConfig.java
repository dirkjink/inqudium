package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqConfig;
import eu.inqudium.core.compatibility.InqCompatibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

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

  // Adaptive concurrency limits
  private final InqLimitAlgorithm limitAlgorithm;

  // FIX #5: Injectable nano-time source for deterministic testing
  private final LongSupplier nanoTimeSource;

  // FIX #9: CoDel-specific configuration fields
  private final Duration codelTargetDelay;
  private final Duration codelInterval;

  private BulkheadConfig(Builder b) {
    this.maxConcurrentCalls = b.maxConcurrentCalls;
    this.maxWaitDuration = b.maxWaitDuration;
    this.compatibility = b.compatibility;
    this.clock = b.clock;
    this.logger = b.logger;
    this.callIdGenerator = b.callIdGenerator;
    this.limitAlgorithm = b.limitAlgorithm;
    this.nanoTimeSource = b.nanoTimeSource;
    this.codelTargetDelay = b.codelTargetDelay;
    this.codelInterval = b.codelInterval;
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

  /**
   * FIX #5: Returns the nano-time source used for RTT measurement and CoDel timing.
   * Defaults to {@code System::nanoTime} but can be replaced for deterministic testing.
   *
   * @return the nano-time supplier
   */
  public LongSupplier getNanoTimeSource() {
    return nanoTimeSource;
  }

  /**
   * FIX #9: Returns the CoDel target delay, or null if CoDel is not configured.
   *
   * @return the target delay or null
   */
  public Duration getCodelTargetDelay() {
    return codelTargetDelay;
  }

  /**
   * FIX #9: Returns the CoDel interval window, or null if CoDel is not configured.
   *
   * @return the interval or null
   */
  public Duration getCodelInterval() {
    return codelInterval;
  }

  /**
   * Returns true if CoDel queue management is configured.
   */
  public boolean isCodelEnabled() {
    return codelTargetDelay != null && codelInterval != null;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    BulkheadConfig that = (BulkheadConfig) o;
    return maxConcurrentCalls == that.maxConcurrentCalls &&
        Objects.equals(maxWaitDuration, that.maxWaitDuration) &&
        Objects.equals(compatibility, that.compatibility) &&
        Objects.equals(limitAlgorithm, that.limitAlgorithm) &&
        Objects.equals(codelTargetDelay, that.codelTargetDelay) &&
        Objects.equals(codelInterval, that.codelInterval);
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

    private Builder() {
    }

    /**
     * FIX #6: Added validation — maxConcurrentCalls must be >= 0.
     * A value of 0 creates a bulkhead that rejects every request immediately,
     * which is a legitimate state (e.g., a "closed" bulkhead or for testing).
     * Negative values cause undefined behavior in Semaphore and are rejected.
     *
     * @param max the maximum number of concurrent calls, must be >= 0
     * @return the builder instance
     * @throws IllegalArgumentException if max is negative
     */
    public Builder maxConcurrentCalls(int max) {
      if (max < 0) {
        throw new IllegalArgumentException(
            "maxConcurrentCalls must be >= 0, but was: " + max);
      }
      this.maxConcurrentCalls = max;
      return this;
    }

    /**
     * FIX #6: Added validation — maxWaitDuration must not be negative.
     * Negative durations would cause immediate timeout failures with misleading behavior.
     *
     * @param duration the maximum wait duration, must be >= 0
     * @return the builder instance
     * @throws NullPointerException     if duration is null
     * @throws IllegalArgumentException if duration is negative
     */
    public Builder maxWaitDuration(Duration duration) {
      Objects.requireNonNull(duration, "maxWaitDuration must not be null");
      if (duration.isNegative()) {
        throw new IllegalArgumentException(
            "maxWaitDuration must not be negative, but was: " + duration);
      }
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
     * <p>FIX #9: Cannot be combined with CoDel configuration. If both are set,
     * {@link #build()} will throw an exception.
     *
     * @param limitAlgorithm the algorithm to use, or null for static limits
     * @return the builder instance
     */
    public Builder limitAlgorithm(InqLimitAlgorithm limitAlgorithm) {
      this.limitAlgorithm = limitAlgorithm;
      return this;
    }

    /**
     * FIX #5: Sets a custom nano-time source.
     * Useful for deterministic testing of CoDel and RTT measurement.
     *
     * @param nanoTimeSource the nano-time supplier
     * @return the builder instance
     */
    public Builder nanoTimeSource(LongSupplier nanoTimeSource) {
      this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
      return this;
    }

    /**
     * FIX #9: Configures CoDel (Controlled Delay) queue management.
     * Both parameters must be set together to enable CoDel.
     *
     * <p>Cannot be combined with a {@link #limitAlgorithm}. If both are set,
     * {@link #build()} will throw an exception.
     *
     * @param targetDelay the acceptable maximum wait time for a request in the queue
     * @param interval    the sliding time window for sustained-congestion detection
     * @return the builder instance
     * @throws IllegalArgumentException if either parameter is negative or zero
     */
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
     * FIX #6 + #9: Added cross-field validation during build.
     *
     * @return the immutable configuration
     * @throws IllegalStateException if incompatible options are combined
     */
    public BulkheadConfig build() {
      // FIX #9: Validate mutual exclusivity of adaptive algorithm and CoDel
      if (limitAlgorithm != null && codelTargetDelay != null) {
        throw new IllegalStateException(
            "Cannot combine a limitAlgorithm with CoDel configuration. "
                + "Use either limitAlgorithm() for adaptive limits (AIMD/Vegas) "
                + "or codel() for queue-based delay management, but not both.");
      }
      return new BulkheadConfig(this);
    }
  }
}
