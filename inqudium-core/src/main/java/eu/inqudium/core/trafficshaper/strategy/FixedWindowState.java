package eu.inqudium.core.trafficshaper.strategy;

import java.time.Duration;
import java.time.Instant;

/**
 * State for {@link FixedWindowStrategy}.
 *
 * <p>Divides time into fixed-size windows. Within each window, up to
 * {@code maxPerWindow} requests are allowed. Excess requests are delayed
 * until the next window starts.
 *
 * @param windowStart      the start instant of the current window
 * @param requestsInWindow number of requests admitted in the current window
 * @param nextFreeSlot     scheduling timeline for delayed requests
 * @param queueDepth       requests currently waiting
 * @param totalAdmitted    total requests admitted
 * @param totalRejected    total requests rejected
 * @param epoch            generation counter for reset invalidation
 */
public record FixedWindowState(
    Instant windowStart,
    int requestsInWindow,
    Instant nextFreeSlot,
    int queueDepth,
    long totalAdmitted,
    long totalRejected,
    long epoch
) implements SchedulingState {

  public static FixedWindowState initial(Instant now) {
    return new FixedWindowState(now, 0, now, 0, 0, 0, 0L);
  }

  public FixedWindowState withNewWindow(Instant newWindowStart) {
    return new FixedWindowState(
        newWindowStart, 0, nextFreeSlot, queueDepth,
        totalAdmitted, totalRejected, epoch);
  }

  public FixedWindowState withAdmittedImmediate() {
    return new FixedWindowState(
        windowStart, requestsInWindow + 1, nextFreeSlot, queueDepth,
        totalAdmitted + 1, totalRejected, epoch);
  }

  public FixedWindowState withAdmittedDelayed(Instant newNextFreeSlot) {
    return new FixedWindowState(
        windowStart, requestsInWindow + 1, newNextFreeSlot, queueDepth + 1,
        totalAdmitted + 1, totalRejected, epoch);
  }

  public FixedWindowState withRequestDequeued() {
    return new FixedWindowState(
        windowStart, requestsInWindow, nextFreeSlot,
        Math.max(0, queueDepth - 1), totalAdmitted, totalRejected, epoch);
  }

  public FixedWindowState withRequestRejected() {
    return new FixedWindowState(
        windowStart, requestsInWindow, nextFreeSlot, queueDepth,
        totalAdmitted, totalRejected + 1, epoch);
  }

  public FixedWindowState withNextEpoch(Instant now) {
    return new FixedWindowState(now, 0, now, 0, totalAdmitted, totalRejected, epoch + 1);
  }

  @Override
  public Duration projectedTailWait(Instant now) {
    if (!nextFreeSlot.isAfter(now)) return Duration.ZERO;
    return Duration.between(now, nextFreeSlot);
  }
}
