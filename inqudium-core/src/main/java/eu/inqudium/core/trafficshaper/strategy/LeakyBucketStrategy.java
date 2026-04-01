package eu.inqudium.core.trafficshaper.strategy;

import eu.inqudium.core.trafficshaper.ThrottleMode;
import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * Leaky bucket scheduling strategy — the default traffic shaping algorithm.
 *
 * <p>Assigns evenly-spaced time slots to incoming requests. Each request
 * advances the scheduling timeline by the configured interval, producing
 * smooth output traffic regardless of how bursty the input is.
 *
 * <pre>
 *   Input:   ──||||||───────||──||──────►  (bursty arrivals)
 *   Output:  ──|──|──|──|──|──|──|──|──►  (smooth, evenly-spaced)
 *                ↑ interval between slots
 * </pre>
 *
 * <p>When a request arrives at time {@code now}:
 * <ol>
 *   <li>If {@code nextFreeSlot <= now}: the request proceeds immediately,
 *       and {@code nextFreeSlot} is set to {@code now + interval}.</li>
 *   <li>If {@code nextFreeSlot > now}: the request must wait until
 *       {@code nextFreeSlot}, and the slot advances by {@code interval}.</li>
 *   <li>If the computed wait exceeds {@code maxWaitDuration} or the queue
 *       depth exceeds {@code maxQueueDepth}, the request is rejected
 *       (in {@link ThrottleMode#SHAPE_AND_REJECT_OVERFLOW} mode).</li>
 * </ol>
 */
public class LeakyBucketStrategy implements SchedulingStrategy<LeakyBucketState> {

  @Override
  public LeakyBucketState initial(TrafficShaperConfig<LeakyBucketState> config, Instant now) {
    return LeakyBucketState.initial(now);
  }

  @Override
  public ThrottlePermission<LeakyBucketState> schedule(
      LeakyBucketState state,
      TrafficShaperConfig<LeakyBucketState> config,
      Instant now) {

    // Reclaim past credits to prevent burst accumulation after idle periods
    LeakyBucketState effective = reclaimSlot(state, config, now);

    // Compute the wait: time from now until the assigned slot
    Duration waitDuration = effective.waitDurationFor(now);
    boolean isImmediate = waitDuration.isZero();

    // If queuing is not allowed (maxQueueDepth=0), any delayed request is rejected
    if (!isImmediate && !config.isQueuingAllowed()
        && config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW) {
      return ThrottlePermission.rejected(
          effective.withRequestRejected(), waitDuration);
    }

    // Check overflow conditions (only in SHAPE_AND_REJECT_OVERFLOW mode)
    if (!isImmediate && config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW) {
      if (shouldReject(effective, config, waitDuration)) {
        return ThrottlePermission.rejected(
            effective.withRequestRejected(), waitDuration);
      }
    }

    // Admit the request: assign the current nextFreeSlot as its execution slot
    Instant assignedSlot = effective.nextFreeSlot();

    // Immediate requests advance the timeline but do NOT increment queueDepth
    if (isImmediate) {
      LeakyBucketState updated = effective.withRequestScheduledImmediate(config.interval());
      return ThrottlePermission.immediate(updated, assignedSlot);
    }

    LeakyBucketState updated = effective.withRequestScheduled(config.interval());
    return ThrottlePermission.delayed(updated, waitDuration, assignedSlot);
  }

  @Override
  public LeakyBucketState recordExecution(LeakyBucketState state) {
    return state.withRequestDequeued();
  }

  @Override
  public LeakyBucketState reset(
      LeakyBucketState state,
      TrafficShaperConfig<LeakyBucketState> config,
      Instant now) {
    return state.withNextEpoch(now);
  }

  @Override
  public Duration estimateWait(
      LeakyBucketState state,
      TrafficShaperConfig<LeakyBucketState> config,
      Instant now) {
    LeakyBucketState effective = reclaimSlot(state, config, now);
    return effective.waitDurationFor(now);
  }

  @Override
  public int queueDepth(LeakyBucketState state) {
    return state.queueDepth();
  }

  @Override
  public boolean isUnboundedQueueWarning(
      LeakyBucketState state,
      TrafficShaperConfig<LeakyBucketState> config,
      Instant now) {
    if (config.throttleMode() != ThrottleMode.SHAPE_UNBOUNDED) {
      return false;
    }
    if (config.unboundedWarnAfter() == null) {
      return false;
    }
    Duration tailWait = state.projectedTailWait(now);
    return tailWait.compareTo(config.unboundedWarnAfter()) > 0;
  }

  // ======================== Internal ========================

  /**
   * Determines whether a request should be rejected based on queue depth
   * and wait duration limits.
   */
  private boolean shouldReject(
      LeakyBucketState state,
      TrafficShaperConfig<LeakyBucketState> config,
      Duration waitDuration) {

    if (config.hasQueueDepthLimit() && state.queueDepth() >= config.maxQueueDepth()) {
      return true;
    }

    if (config.hasMaxWaitDurationLimit()
        && waitDuration.compareTo(config.maxWaitDuration()) > 0) {
      return true;
    }

    return false;
  }

  /**
   * Reclaims unused time slots when the scheduling timeline has fallen
   * behind the current time. Prevents burst accumulation after idle periods.
   *
   * <p>When {@code queueDepth > 0}, clamps the slot to
   * {@code now - (queueDepth * interval)} to preserve queue timing
   * obligations while preventing credit build-up.
   */
  private LeakyBucketState reclaimSlot(
      LeakyBucketState state,
      TrafficShaperConfig<LeakyBucketState> config,
      Instant now) {

    if (!state.nextFreeSlot().isBefore(now)) {
      return state;
    }

    if (state.queueDepth() == 0) {
      return state.withNextFreeSlot(now);
    }

    // Clamp so existing queue obligations are preserved but no burst credit accumulates
    Instant clampedSlot = now.minus(config.interval().multipliedBy(state.queueDepth()));
    if (clampedSlot.isAfter(state.nextFreeSlot())) {
      return state.withNextFreeSlot(clampedSlot);
    }

    return state;
  }
}
