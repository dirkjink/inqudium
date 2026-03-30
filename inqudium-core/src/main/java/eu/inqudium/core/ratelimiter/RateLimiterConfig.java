package eu.inqudium.core.ratelimiter;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqConfig;
import eu.inqudium.core.compatibility.InqCompatibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for the Rate Limiter element (ADR-019).
 *
 * @since 0.1.0
 */
public final class RateLimiterConfig implements InqConfig {

  private static final RateLimiterConfig DEFAULTS = RateLimiterConfig.builder().build();

  private final int limitForPeriod;
  private final Duration limitRefreshPeriod;
  private final int bucketSize;
  private final Duration timeoutDuration;
  private final InqClock clock;
  private final InqCompatibility compatibility;
  private final Logger logger;
  private final InqCallIdGenerator callIdGenerator;

  private RateLimiterConfig(Builder b) {
    this.limitForPeriod = b.limitForPeriod;
    this.limitRefreshPeriod = b.limitRefreshPeriod;
    this.bucketSize = b.bucketSize;
    this.timeoutDuration = b.timeoutDuration;
    this.clock = b.clock;
    this.compatibility = b.compatibility;
    this.logger = b.logger;
    this.callIdGenerator = b.callIdGenerator;
  }

  public static RateLimiterConfig ofDefaults() {
    return DEFAULTS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int getLimitForPeriod() {
    return limitForPeriod;
  }

  public Duration getLimitRefreshPeriod() {
    return limitRefreshPeriod;
  }

  public int getBucketSize() {
    return bucketSize;
  }

  public Duration getTimeoutDuration() {
    return timeoutDuration;
  }

  public InqClock getClock() {
    return clock;
  }

  @Override
  public InqCompatibility getCompatibility() {
    return compatibility;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }

  @Override
  public InqCallIdGenerator getCallIdGenerator() {
    return callIdGenerator;
  }

  public static final class Builder {
    private int limitForPeriod = 50;
    private Duration limitRefreshPeriod = Duration.ofMillis(500);
    private int bucketSize = -1; // sentinel: defaults to limitForPeriod
    private Duration timeoutDuration = Duration.ZERO;
    private InqClock clock = InqClock.system();
    private InqCompatibility compatibility = InqCompatibility.ofDefaults();
    private Logger logger = LoggerFactory.getLogger(RateLimiterConfig.class);
    private InqCallIdGenerator callIdGenerator = InqCallIdGenerator.uuid();

    private Builder() {
    }

    public Builder limitForPeriod(int limitForPeriod) {
      this.limitForPeriod = limitForPeriod;
      return this;
    }

    public Builder limitRefreshPeriod(Duration period) {
      this.limitRefreshPeriod = Objects.requireNonNull(period);
      return this;
    }

    public Builder bucketSize(int bucketSize) {
      this.bucketSize = bucketSize;
      return this;
    }

    public Builder timeoutDuration(Duration timeout) {
      this.timeoutDuration = Objects.requireNonNull(timeout);
      return this;
    }

    public Builder clock(InqClock clock) {
      this.clock = Objects.requireNonNull(clock);
      return this;
    }

    public Builder compatibility(InqCompatibility c) {
      this.compatibility = Objects.requireNonNull(c);
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

    public RateLimiterConfig build() {
      if (bucketSize < 0) bucketSize = limitForPeriod;
      return new RateLimiterConfig(this);
    }
  }
}
