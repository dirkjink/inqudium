package eu.inqudium.core.trafficshaper;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a traffic shaper instance.
 *
 * <p>The traffic shaper uses a <strong>leaky bucket scheduler</strong>:
 * incoming requests are assigned successive time slots spaced
 * {@link #interval()} apart. Each request is delayed until its slot
 * arrives, producing smooth, evenly-spaced output traffic regardless
 * of how bursty the input is.
 *
 * <p>Use {@link #builder(String)} to construct.
 *
 * @param name               a human-readable identifier
 * @param ratePerSecond      the target throughput in requests per second
 * @param interval           the computed interval between successive slots
 *                           ({@code Duration.ofNanos(1_000_000_000 / ratePerSecond)})
 * @param maxQueueDepth      maximum number of requests that may be waiting
 *                           simultaneously; 0 = no queue (immediate or reject);
 *                           -1 = unlimited (only maxWaitDuration applies)
 * @param maxWaitDuration    maximum time a single request may wait before
 *                           being rejected; {@link Duration#ZERO} means no limit
 *                           on wait time (only queue depth applies)
 * @param throttleMode       how to handle overflow (reject vs. unbounded)
 * @param unboundedWarnAfter in SHAPE_UNBOUNDED mode, emit a warning event
 *                           when projected tail wait exceeds this duration;
 *                           {@code null} disables the warning
 */
public record TrafficShaperConfig(
    String name,
    double ratePerSecond,
    Duration interval,
    int maxQueueDepth,
    Duration maxWaitDuration,
    ThrottleMode throttleMode,
    Duration unboundedWarnAfter
) {

  public TrafficShaperConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(interval, "interval must not be null");
    Objects.requireNonNull(maxWaitDuration, "maxWaitDuration must not be null");
    Objects.requireNonNull(throttleMode, "throttleMode must not be null");
    if (ratePerSecond <= 0) {
      throw new IllegalArgumentException("ratePerSecond must be positive, got " + ratePerSecond);
    }
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive");
    }
    // maxQueueDepth semantics:
    //   -1 = unlimited (only maxWaitDuration applies)
    //    0 = no queuing allowed (immediate or reject)
    //   >0 = max N requests waiting
    if (maxQueueDepth < -1) {
      throw new IllegalArgumentException("maxQueueDepth must be >= -1, got " + maxQueueDepth);
    }
    if (maxWaitDuration.isNegative()) {
      throw new IllegalArgumentException("maxWaitDuration must not be negative");
    }
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  /**
   * Returns whether queuing is allowed at all.
   * When maxQueueDepth is 0, requests are either immediate or rejected — never delayed.
   */
  public boolean isQueuingAllowed() {
    return maxQueueDepth != 0;
  }

  /**
   * Returns whether the queue depth limit is enforced.
   * A limit of -1 means "unlimited" (only maxWaitDuration applies).
   */
  public boolean hasQueueDepthLimit() {
    return maxQueueDepth > 0;
  }

  /**
   * Fix 8: Returns whether a maximum wait duration limit is enforced.
   * {@link Duration#ZERO} disables the wait duration check — only queue depth applies.
   *
   * <p>This is intentionally the opposite of what ZERO might intuitively suggest.
   * The rationale: a traffic shaper that rejects on any delay is better expressed
   * via {@code maxQueueDepth(0)}, which means "no queuing allowed". Setting
   * {@code maxWaitDuration(ZERO)} alongside {@code maxQueueDepth(-1)} means
   * "unlimited queue, no wait limit" — the shaper delays everything and never
   * rejects (equivalent to SHAPE_UNBOUNDED mode with queue depth tracking).
   */
  public boolean hasMaxWaitDurationLimit() {
    return !maxWaitDuration.isZero();
  }

  public static final class Builder {
    private final String name;
    private double ratePerSecond = 10.0;
    private int maxQueueDepth = 50;
    private Duration maxWaitDuration = Duration.ofSeconds(10);
    private ThrottleMode throttleMode = ThrottleMode.SHAPE_AND_REJECT_OVERFLOW;
    private Duration unboundedWarnAfter = Duration.ofMinutes(1);

    // Store raw count/period for precision-preserving interval computation
    private Integer rawCount = null;
    private Duration rawPeriod = null;

    private Builder(String name) {
      this.name = Objects.requireNonNull(name);
    }

    /**
     * Sets the target throughput in requests per second.
     * The interval between slots is computed automatically.
     *
     * <p><strong>Fix 11:</strong> Rates above ~1 billion req/s produce a 1ns interval,
     * which effectively disables shaping. This is validated at build time.
     */
    public Builder ratePerSecond(double ratePerSecond) {
      this.ratePerSecond = ratePerSecond;
      this.rawCount = null;
      this.rawPeriod = null;
      return this;
    }

    /**
     * Convenience: sets the rate from a count and a period.
     * E.g. {@code rateForPeriod(100, Duration.ofMinutes(1))} = 100 req/min.
     *
     * <p>The raw count and period are stored and used directly for interval
     * computation in {@link #build()}, avoiding the double-precision roundtrip
     * through {@code ratePerSecond}.
     */
    public Builder rateForPeriod(int count, Duration period) {
      if (count < 1) {
        throw new IllegalArgumentException("count must be >= 1, got " + count);
      }
      Objects.requireNonNull(period, "period must not be null");
      if (period.isNegative() || period.isZero()) {
        throw new IllegalArgumentException("period must be positive");
      }
      this.ratePerSecond = (double) count / ((double) period.toNanos() / 1_000_000_000.0);
      this.rawCount = count;
      this.rawPeriod = period;
      return this;
    }

    /**
     * Maximum number of requests that may be queued waiting for their slot.
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code -1} = unlimited queue (only maxWaitDuration applies)</li>
     *   <li>{@code 0} = no queuing allowed (immediate or reject, never delay)</li>
     *   <li>{@code > 0} = max N requests may wait simultaneously</li>
     * </ul>
     */
    public Builder maxQueueDepth(int maxQueueDepth) {
      this.maxQueueDepth = maxQueueDepth;
      return this;
    }

    /**
     * Maximum time any single request may wait. Requests whose computed
     * wait exceeds this are rejected regardless of queue depth.
     *
     * <p><strong>Fix 8:</strong> {@link Duration#ZERO} disables the wait duration
     * check entirely — only queue depth limits apply. This is intentional:
     * "no waiting allowed" is expressed via {@code maxQueueDepth(0)}, not via
     * a zero maxWaitDuration. See {@link TrafficShaperConfig#hasMaxWaitDurationLimit()}.
     */
    public Builder maxWaitDuration(Duration maxWaitDuration) {
      this.maxWaitDuration = maxWaitDuration;
      return this;
    }

    public Builder throttleMode(ThrottleMode throttleMode) {
      this.throttleMode = throttleMode;
      return this;
    }

    /**
     * In SHAPE_UNBOUNDED mode, emit a warning event when the projected
     * tail wait exceeds this duration. Set to {@code null} to disable.
     * Default: 1 minute.
     */
    public Builder unboundedWarnAfter(Duration unboundedWarnAfter) {
      this.unboundedWarnAfter = unboundedWarnAfter;
      return this;
    }

    public TrafficShaperConfig build() {
      long intervalNanos;

      // When rateForPeriod was used, compute interval directly from
      // the raw integer count and period nanos to avoid double-precision loss
      if (rawCount != null && rawPeriod != null) {
        intervalNanos = rawPeriod.toNanos() / rawCount;
      } else {
        intervalNanos = (long) (1_000_000_000.0 / ratePerSecond);
      }

      // Fix 11: Ensure the interval is at least 1 microsecond.
      // Anything below that provides no meaningful shaping — the scheduling
      // overhead exceeds the interval itself. A 1ns interval at ~1 billion req/s
      // effectively disables shaping; we clamp at 1µs (1 million req/s practical max).
      long minimumIntervalNanos = 1_000; // 1 microsecond
      if (intervalNanos < minimumIntervalNanos) {
        intervalNanos = minimumIntervalNanos;
      }

      Duration interval = Duration.ofNanos(intervalNanos);
      return new TrafficShaperConfig(
          name, ratePerSecond, interval, maxQueueDepth, maxWaitDuration,
          throttleMode, unboundedWarnAfter);
    }
  }
}
