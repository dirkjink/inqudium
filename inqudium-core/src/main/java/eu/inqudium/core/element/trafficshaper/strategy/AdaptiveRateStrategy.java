package eu.inqudium.core.element.trafficshaper.strategy;

import eu.inqudium.core.element.trafficshaper.ThrottleMode;
import eu.inqudium.core.element.trafficshaper.ThrottlePermission;
import eu.inqudium.core.element.trafficshaper.TrafficShaperConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * Adaptive rate shaping strategy — adjusts the interval based on downstream feedback.
 *
 * <p>Starts at the configured interval and adapts:
 * <ul>
 *   <li><strong>On success:</strong> After {@code successesBeforeIncrease} consecutive
 *       successes, the interval is decreased by {@code decreaseFactor}
 *       (rate increases), down to {@code minIntervalNanos}.</li>
 *   <li><strong>On failure:</strong> The interval is immediately increased by
 *       {@code increaseFactor} (rate decreases), up to {@code maxIntervalNanos}.</li>
 * </ul>
 *
 * <p>The scheduling logic is identical to {@link LeakyBucketStrategy} (evenly-spaced
 * slots), but the slot spacing adapts over time based on the health of the downstream.
 *
 * <p><strong>Usage:</strong> The imperative wrapper must call
 * {@link #recordSuccess(AdaptiveRateState)} and {@link #recordFailure(AdaptiveRateState)}
 * after each execution to feed back the outcome. Without feedback, the strategy
 * behaves like a static leaky bucket.
 *
 * @param decreaseFactor          multiplier for interval on success (e.g., 0.9 = 10% faster)
 * @param increaseFactor          multiplier for interval on failure (e.g., 2.0 = half speed)
 * @param minIntervalNanos        floor for the dynamic interval
 * @param maxIntervalNanos        ceiling for the dynamic interval
 * @param successesBeforeIncrease consecutive successes needed before rate increases
 */
public record AdaptiveRateStrategy(
    double decreaseFactor,
    double increaseFactor,
    long minIntervalNanos,
    long maxIntervalNanos,
    int successesBeforeIncrease
) implements SchedulingStrategy<AdaptiveRateState> {

  public AdaptiveRateStrategy {
    if (decreaseFactor <= 0 || decreaseFactor >= 1.0) {
      throw new IllegalArgumentException("decreaseFactor must be in (0, 1), got " + decreaseFactor);
    }
    if (increaseFactor <= 1.0) {
      throw new IllegalArgumentException("increaseFactor must be > 1, got " + increaseFactor);
    }
    if (minIntervalNanos < 1) {
      throw new IllegalArgumentException("minIntervalNanos must be >= 1");
    }
    if (maxIntervalNanos < minIntervalNanos) {
      throw new IllegalArgumentException("maxIntervalNanos must be >= minIntervalNanos");
    }
    if (successesBeforeIncrease < 1) {
      throw new IllegalArgumentException("successesBeforeIncrease must be >= 1");
    }
  }

  /**
   * Creates a strategy with sensible defaults:
   * decrease 10% on success (after 3 consecutive), double on failure.
   */
  public static AdaptiveRateStrategy withDefaults(Duration minInterval, Duration maxInterval) {
    return new AdaptiveRateStrategy(
        0.9, 2.0, minInterval.toNanos(), maxInterval.toNanos(), 3);
  }

  @Override
  public AdaptiveRateState initial(TrafficShaperConfig<AdaptiveRateState> config, Instant now) {
    return AdaptiveRateState.initial(config.interval().toNanos(), now);
  }

  @Override
  public ThrottlePermission<AdaptiveRateState> schedule(
      AdaptiveRateState state,
      TrafficShaperConfig<AdaptiveRateState> config,
      Instant now) {

    // Reclaim stale slot (same logic as leaky bucket)
    AdaptiveRateState effective = reclaimSlot(state, now);
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
  public AdaptiveRateState recordExecution(AdaptiveRateState state) {
    return state.withRequestDequeued();
  }

  @Override
  public AdaptiveRateState recordSuccess(AdaptiveRateState state) {
    int newSuccesses = state.consecutiveSuccesses() + 1;
    if (newSuccesses >= successesBeforeIncrease) {
      // Decrease interval (increase rate)
      long newInterval = Math.max(
          (long) (state.currentIntervalNanos() * decreaseFactor),
          minIntervalNanos);
      return state.withInterval(newInterval, 0, 0);
    }
    return state.withInterval(state.currentIntervalNanos(), newSuccesses, 0);
  }

  @Override
  public AdaptiveRateState recordFailure(AdaptiveRateState state) {
    // Increase interval immediately (decrease rate)
    long newInterval = Math.min(
        (long) (state.currentIntervalNanos() * increaseFactor),
        maxIntervalNanos);
    return state.withInterval(newInterval, 0, state.consecutiveFailures() + 1);
  }

  @Override
  public AdaptiveRateState reset(
      AdaptiveRateState state,
      TrafficShaperConfig<AdaptiveRateState> config,
      Instant now) {
    return state.withNextEpoch(config.interval().toNanos(), now);
  }

  @Override
  public Duration estimateWait(
      AdaptiveRateState state,
      TrafficShaperConfig<AdaptiveRateState> config,
      Instant now) {
    return waitFor(reclaimSlot(state, now), now);
  }

  @Override
  public int queueDepth(AdaptiveRateState state) {
    return state.queueDepth();
  }

  @Override
  public boolean isUnboundedQueueWarning(
      AdaptiveRateState state,
      TrafficShaperConfig<AdaptiveRateState> config,
      Instant now) {
    return checkUnboundedWarning(state, config, now);
  }

  private AdaptiveRateState reclaimSlot(AdaptiveRateState state, Instant now) {
    if (state.nextFreeSlot().isBefore(now) && state.queueDepth() == 0) {
      return state.withNextFreeSlot(now);
    }
    return state;
  }

  private Duration waitFor(AdaptiveRateState state, Instant now) {
    Duration wait = Duration.between(now, state.nextFreeSlot());
    return wait.isNegative() ? Duration.ZERO : wait;
  }
}
