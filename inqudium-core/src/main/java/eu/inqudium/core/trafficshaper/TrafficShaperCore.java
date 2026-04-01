package eu.inqudium.core.trafficshaper;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure functional core of the traffic shaper (leaky bucket scheduler).
 *
 * <p>All methods are static and side-effect-free. They accept the current
 * immutable {@link ThrottleSnapshot} and return a new snapshot reflecting
 * the scheduling decision. No synchronization, no I/O, no mutation.
 *
 * <h2>Leaky Bucket Scheduling Algorithm</h2>
 * <p>The core maintains a virtual timeline of evenly-spaced slots. Each
 * incoming request is assigned the next available slot:
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
 *
 * <p><strong>Event transactionality note (Fix 7):</strong> The imperative wrapper
 * emits rejection events <em>before</em> committing the snapshot via CAS. This means
 * a listener may observe a REJECTED event even though the rejection was never committed
 * (because another thread changed the state concurrently). Rejection events should be
 * treated as <em>best-effort observations</em>, not as transactional guarantees. The
 * {@code totalRejected} counter in the snapshot is the authoritative source.
 */
public final class TrafficShaperCore {

  private TrafficShaperCore() {
    // Utility class — not instantiable
  }

  // ======================== Scheduling ========================

  /**
   * Schedules a request and returns the throttling decision.
   *
   * @param snapshot the current shared scheduling state
   * @param config   the traffic shaper configuration
   * @param now      the current timestamp (arrival time of the request)
   * @return a throttle permission with the updated snapshot
   */
  public static ThrottlePermission schedule(
      ThrottleSnapshot snapshot,
      TrafficShaperConfig config,
      Instant now) {

    // Reclaim past credits to prevent burst accumulation after idle periods
    ThrottleSnapshot effective = reclaimSlot(snapshot, config, now);

    // Compute the wait: time from now until the assigned slot
    Duration waitDuration = effective.waitDurationFor(now);
    boolean isImmediate = waitDuration.isZero();

    // If queuing is not allowed (maxQueueDepth=0), any request that
    // would require waiting must be rejected immediately
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
      ThrottleSnapshot updated = effective.withRequestScheduledImmediate(config.interval());
      return ThrottlePermission.immediate(updated, assignedSlot);
    }

    ThrottleSnapshot updated = effective.withRequestScheduled(config.interval());
    return ThrottlePermission.delayed(updated, waitDuration, assignedSlot);
  }

  /**
   * Records that a previously queued request has left the queue and
   * started executing. Decrements the queue depth.
   *
   * @param snapshot the current snapshot
   * @return the updated snapshot with decremented queue depth
   */
  public static ThrottleSnapshot recordExecution(ThrottleSnapshot snapshot) {
    return snapshot.withRequestDequeued();
  }

  // ======================== Overflow Detection ========================

  /**
   * Determines whether a request should be rejected based on queue depth
   * and wait duration limits.
   *
   * <p>This check evaluates the current state BEFORE the request is scheduled.
   * The semantics are: "is there room for one more waiting request?"
   *
   * <p>Fix 8: Uses {@link TrafficShaperConfig#hasMaxWaitDurationLimit()} to
   * correctly handle the documented ZERO semantics (ZERO = no limit).
   */
  static boolean shouldReject(
      ThrottleSnapshot snapshot,
      TrafficShaperConfig config,
      Duration waitDuration) {

    // Only check queue depth when a positive limit is configured
    if (config.hasQueueDepthLimit() && snapshot.queueDepth() >= config.maxQueueDepth()) {
      return true;
    }

    // Fix 8: Only check wait duration when a limit is configured (ZERO = no limit)
    if (config.hasMaxWaitDurationLimit()
        && waitDuration.compareTo(config.maxWaitDuration()) > 0) {
      return true;
    }

    return false;
  }

  // ======================== Slot Reclamation ========================

  /**
   * Reclaims unused time slots when the scheduling timeline has fallen
   * behind the current time.
   *
   * <p>Without reclamation, a traffic shaper that was idle for 10 seconds
   * would allow the next 10 seconds' worth of requests to pass without
   * delay, defeating the purpose of traffic shaping.
   *
   * <p><strong>Fix 5:</strong> When {@code queueDepth > 0}, the slot is clamped
   * to {@code now - (queueDepth * interval)} instead of being left unchanged.
   * This prevents burst accumulation while still honouring the timing obligations
   * of already-queued requests. Without this fix, if a shaper was idle for 5 seconds
   * with stale queue entries, the next 5 seconds' worth of requests would get
   * past-dated slots and pass through immediately as an uncontrolled burst.
   *
   * @param snapshot the current snapshot
   * @param config   the configuration (for the interval)
   * @param now      the current time
   * @return the snapshot with the slot reclaimed (or unchanged)
   */
  public static ThrottleSnapshot reclaimSlot(
      ThrottleSnapshot snapshot,
      TrafficShaperConfig config,
      Instant now) {

    if (!snapshot.nextFreeSlot().isBefore(now)) {
      // Slot is in the future or exactly now — nothing to reclaim
      return snapshot;
    }

    if (snapshot.queueDepth() == 0) {
      // No one waiting — safe to reclaim fully to prevent burst credit
      return snapshot.withNextFreeSlot(now);
    }

    // Fix 5: Queue has pending requests but the slot drifted into the past.
    // Clamp nextFreeSlot so that existing queue obligations are preserved
    // (each queued request still gets its interval-spaced slot relative to now)
    // but no additional "burst credit" accumulates.
    Instant clampedSlot = now.minus(config.interval().multipliedBy(snapshot.queueDepth()));
    if (clampedSlot.isAfter(snapshot.nextFreeSlot())) {
      return snapshot.withNextFreeSlot(clampedSlot);
    }

    // The slot hasn't drifted far enough to matter — keep as-is
    return snapshot;
  }

  // ======================== Query Helpers ========================

  /**
   * Returns the current queue depth.
   */
  public static int queueDepth(ThrottleSnapshot snapshot) {
    return snapshot.queueDepth();
  }

  /**
   * Returns the estimated wait time for a request arriving now.
   * Does <strong>not</strong> modify the snapshot.
   */
  public static Duration estimateWait(
      ThrottleSnapshot snapshot, TrafficShaperConfig config, Instant now) {
    ThrottleSnapshot effective = reclaimSlot(snapshot, config, now);
    return effective.waitDurationFor(now);
  }

  /**
   * Returns the current effective throughput based on the configured rate.
   */
  public static double currentRatePerSecond(TrafficShaperConfig config) {
    return config.ratePerSecond();
  }

  /**
   * Fix 9: Resets the traffic shaper to its initial state with an incremented epoch.
   * Parked threads detect the epoch change and re-acquire their slot.
   */
  public static ThrottleSnapshot reset(ThrottleSnapshot current, Instant now) {
    return current.withNextEpoch(now);
  }

  /**
   * Checks whether the unbounded queue has grown beyond the warning threshold.
   *
   * @param snapshot the current snapshot
   * @param config   the configuration (contains the warning threshold)
   * @param now      the current time
   * @return true if the projected tail wait exceeds the configured warning threshold
   */
  public static boolean isUnboundedQueueWarning(
      ThrottleSnapshot snapshot,
      TrafficShaperConfig config,
      Instant now) {
    if (config.throttleMode() != ThrottleMode.SHAPE_UNBOUNDED) {
      return false;
    }
    if (config.unboundedWarnAfter() == null) {
      return false;
    }
    Duration tailWait = snapshot.projectedTailWait(now);
    return tailWait.compareTo(config.unboundedWarnAfter()) > 0;
  }
}
