package eu.inqudium.core.element.trafficshaper.strategy;

import eu.inqudium.core.element.trafficshaper.ThrottleMode;
import eu.inqudium.core.element.trafficshaper.ThrottlePermission;
import eu.inqudium.core.element.trafficshaper.TrafficShaperConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * AIMD (Additive Increase, Multiplicative Decrease) shaping strategy.
 *
 * <p>Adapts the shaping rate using the same algorithm as TCP congestion control:
 * <ul>
 *   <li><strong>Additive increase:</strong> On each success, the interval is
 *       decreased by a fixed amount ({@code decrementNanos}), linearly increasing
 *       the rate toward the optimum.</li>
 *   <li><strong>Multiplicative decrease:</strong> On each failure, the interval is
 *       multiplied by {@code increaseMultiplier} (e.g., 2.0 = halve the rate),
 *       rapidly backing off from overload.</li>
 * </ul>
 *
 * <p>This produces the characteristic sawtooth rate pattern that TCP is known for.
 * It converges to the sustainable rate without requiring manual configuration
 * of the optimal rate — only the boundaries (min/max interval) need to be set.
 *
 * <p><strong>Key difference from {@link AdaptiveRateStrategy}:</strong> AIMD uses
 * a fixed additive decrement per success (linear ramp-up) and a multiplicative
 * increase per failure (exponential back-off). Adaptive rate uses multiplicative
 * factors in both directions (proportional adjustment). AIMD converges more slowly
 * but is more stable; Adaptive rate reacts faster but can oscillate.
 *
 * @param decrementNanos     nanoseconds subtracted from the interval on each success
 * @param increaseMultiplier multiplier applied to the interval on each failure
 * @param minIntervalNanos   floor for the dynamic interval (max rate)
 * @param maxIntervalNanos   ceiling for the dynamic interval (min rate)
 */
public record AimdStrategy(
    long decrementNanos,
    double increaseMultiplier,
    long minIntervalNanos,
    long maxIntervalNanos
) implements SchedulingStrategy<AimdState> {

  public AimdStrategy {
    if (decrementNanos < 1) {
      throw new IllegalArgumentException("decrementNanos must be >= 1");
    }
    if (increaseMultiplier <= 1.0) {
      throw new IllegalArgumentException("increaseMultiplier must be > 1, got " + increaseMultiplier);
    }
    if (minIntervalNanos < 1) {
      throw new IllegalArgumentException("minIntervalNanos must be >= 1");
    }
    if (maxIntervalNanos < minIntervalNanos) {
      throw new IllegalArgumentException("maxIntervalNanos must be >= minIntervalNanos");
    }
  }

  /**
   * Creates a strategy with sensible defaults:
   * decrement 1ms per success, double on failure.
   */
  public static AimdStrategy withDefaults(Duration minInterval, Duration maxInterval) {
    return new AimdStrategy(
        Duration.ofMillis(1).toNanos(),
        2.0,
        minInterval.toNanos(),
        maxInterval.toNanos());
  }

  @Override
  public AimdState initial(TrafficShaperConfig<AimdState> config, Instant now) {
    return AimdState.initial(config.interval().toNanos(), now);
  }

  @Override
  public ThrottlePermission<AimdState> schedule(
      AimdState state,
      TrafficShaperConfig<AimdState> config,
      Instant now) {

    AimdState effective = reclaimSlot(state, now);
    Duration currentInterval = effective.currentInterval();

    Duration waitDuration = waitFor(effective, now);
    boolean isImmediate = waitDuration.isZero();

    if (!isImmediate && !config.isQueuingAllowed()
        && config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW) {
      return ThrottlePermission.rejected(effective.withRequestRejected(), waitDuration);
    }
    if (!isImmediate && config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW
        && shouldReject(effective, config, waitDuration)) {
      return ThrottlePermission.rejected(effective.withRequestRejected(), waitDuration);
    }

    Instant assignedSlot = effective.nextFreeSlot();
    Instant newNextFreeSlot = (isImmediate ? now : assignedSlot).plus(currentInterval);

    if (isImmediate) {
      return ThrottlePermission.immediate(
          effective.withAdmittedImmediate(newNextFreeSlot), assignedSlot);
    }
    return ThrottlePermission.delayed(
        effective.withAdmittedDelayed(newNextFreeSlot), waitDuration, assignedSlot);
  }

  @Override
  public AimdState recordExecution(AimdState state) {
    return state.withRequestDequeued();
  }

  /**
   * Additive increase: subtract a fixed amount from the interval.
   * The rate increases linearly with each success.
   */
  @Override
  public AimdState recordSuccess(AimdState state) {
    long newInterval = Math.max(state.currentIntervalNanos() - decrementNanos, minIntervalNanos);
    return state.withInterval(newInterval);
  }

  /**
   * Multiplicative decrease: multiply the interval by the increase factor.
   * The rate drops exponentially on failure, rapidly backing off.
   */
  @Override
  public AimdState recordFailure(AimdState state) {
    long newInterval = (long) (state.currentIntervalNanos() * increaseMultiplier);
    // Guard against overflow
    if (newInterval < 0 || newInterval > maxIntervalNanos) {
      newInterval = maxIntervalNanos;
    }
    return state.withInterval(newInterval);
  }

  @Override
  public AimdState reset(
      AimdState state,
      TrafficShaperConfig<AimdState> config,
      Instant now) {
    return state.withNextEpoch(config.interval().toNanos(), now);
  }

  @Override
  public Duration estimateWait(
      AimdState state,
      TrafficShaperConfig<AimdState> config,
      Instant now) {
    return waitFor(reclaimSlot(state, now), now);
  }

  @Override
  public int queueDepth(AimdState state) {
    return state.queueDepth();
  }

  @Override
  public boolean isUnboundedQueueWarning(
      AimdState state,
      TrafficShaperConfig<AimdState> config,
      Instant now) {
    return checkUnboundedWarning(state, config, now);
  }

  private AimdState reclaimSlot(AimdState state, Instant now) {
    if (state.nextFreeSlot().isBefore(now) && state.queueDepth() == 0) {
      return state.withNextFreeSlot(now);
    }
    return state;
  }

  private Duration waitFor(AimdState state, Instant now) {
    Duration wait = Duration.between(now, state.nextFreeSlot());
    return wait.isNegative() ? Duration.ZERO : wait;
  }
}
