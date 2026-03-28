package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.InqConfig;
import eu.inqudium.core.compatibility.InqCompatibility;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for the Circuit Breaker element (ADR-016).
 *
 * @since 0.1.0
 */
public final class CircuitBreakerConfig implements InqConfig {

  private static final CircuitBreakerConfig DEFAULTS = CircuitBreakerConfig.builder().build();
  private final float failureRateThreshold;
  private final float slowCallRateThreshold;
  private final Duration slowCallDurationThreshold;
  private final SlidingWindowType slidingWindowType;
  private final int slidingWindowSize;
  private final int minimumNumberOfCalls;
  private final Duration waitDurationInOpenState;
  private final int permittedNumberOfCallsInHalfOpenState;
  private final InqClock clock;
  private final InqCompatibility compatibility;
  private CircuitBreakerConfig(Builder b) {
    this.failureRateThreshold = b.failureRateThreshold;
    this.slowCallRateThreshold = b.slowCallRateThreshold;
    this.slowCallDurationThreshold = b.slowCallDurationThreshold;
    this.slidingWindowType = b.slidingWindowType;
    this.slidingWindowSize = b.slidingWindowSize;
    this.minimumNumberOfCalls = b.minimumNumberOfCalls;
    this.waitDurationInOpenState = b.waitDurationInOpenState;
    this.permittedNumberOfCallsInHalfOpenState = b.permittedNumberOfCallsInHalfOpenState;
    this.clock = b.clock;
    this.compatibility = b.compatibility;
  }

  public static CircuitBreakerConfig ofDefaults() {
    return DEFAULTS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public float getFailureRateThreshold() {
    return failureRateThreshold;
  }

  public float getSlowCallRateThreshold() {
    return slowCallRateThreshold;
  }

  public Duration getSlowCallDurationThreshold() {
    return slowCallDurationThreshold;
  }

  public SlidingWindowType getSlidingWindowType() {
    return slidingWindowType;
  }

  public int getSlidingWindowSize() {
    return slidingWindowSize;
  }

  public int getMinimumNumberOfCalls() {
    return minimumNumberOfCalls;
  }

  public Duration getWaitDurationInOpenState() {
    return waitDurationInOpenState;
  }

  public int getPermittedNumberOfCallsInHalfOpenState() {
    return permittedNumberOfCallsInHalfOpenState;
  }

  public InqClock getClock() {
    return clock;
  }

  @Override
  public InqCompatibility getCompatibility() {
    return compatibility;
  }

  /**
   * Creates the appropriate sliding window for this configuration.
   *
   * @return a new sliding window instance
   */
  public SlidingWindow createSlidingWindow() {
    long thresholdNanos = slowCallDurationThreshold.toNanos();
    return switch (slidingWindowType) {
      case COUNT_BASED -> new CountBasedSlidingWindow(slidingWindowSize, thresholdNanos);
      case TIME_BASED -> new TimeBasedSlidingWindow(slidingWindowSize, thresholdNanos, clock);
    };
  }

  /**
   * Sliding window type.
   */
  public enum SlidingWindowType {COUNT_BASED, TIME_BASED}

  public static final class Builder {
    private float failureRateThreshold = 50.0f;
    private float slowCallRateThreshold = 100.0f;
    private Duration slowCallDurationThreshold = Duration.ofSeconds(60);
    private SlidingWindowType slidingWindowType = SlidingWindowType.COUNT_BASED;
    private int slidingWindowSize = 100;
    private int minimumNumberOfCalls = 100;
    private Duration waitDurationInOpenState = Duration.ofSeconds(60);
    private int permittedNumberOfCallsInHalfOpenState = 10;
    private InqClock clock = InqClock.system();
    private InqCompatibility compatibility = InqCompatibility.ofDefaults();

    private Builder() {
    }

    public Builder failureRateThreshold(float threshold) {
      this.failureRateThreshold = threshold;
      return this;
    }

    public Builder slowCallRateThreshold(float threshold) {
      this.slowCallRateThreshold = threshold;
      return this;
    }

    public Builder slowCallDurationThreshold(Duration duration) {
      this.slowCallDurationThreshold = Objects.requireNonNull(duration);
      return this;
    }

    public Builder slidingWindowType(SlidingWindowType type) {
      this.slidingWindowType = Objects.requireNonNull(type);
      return this;
    }

    public Builder slidingWindowSize(int size) {
      this.slidingWindowSize = size;
      return this;
    }

    public Builder minimumNumberOfCalls(int min) {
      this.minimumNumberOfCalls = min;
      return this;
    }

    public Builder waitDurationInOpenState(Duration duration) {
      this.waitDurationInOpenState = Objects.requireNonNull(duration);
      return this;
    }

    public Builder permittedNumberOfCallsInHalfOpenState(int count) {
      this.permittedNumberOfCallsInHalfOpenState = count;
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

    public CircuitBreakerConfig build() {
      return new CircuitBreakerConfig(this);
    }
  }
}
