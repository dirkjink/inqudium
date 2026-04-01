package eu.inqudium.core.trafficshaper.strategy;

import java.time.Duration;
import java.time.Instant;

/**
 * State for {@link AdaptiveRateStrategy}.
 *
 * <p>Carries a dynamic interval that is adjusted based on downstream
 * success/failure signals. The interval shrinks on success (faster rate)
 * and grows on failure (slower rate, backpressure).
 *
 * @param nextFreeSlot         scheduling timeline for the next request
 * @param currentIntervalNanos the dynamically adjusted interval in nanoseconds
 * @param consecutiveSuccesses consecutive successes since last failure (for step-up)
 * @param consecutiveFailures  consecutive failures since last success (for step-down)
 * @param queueDepth           requests currently waiting
 * @param totalAdmitted        total requests admitted
 * @param totalRejected        total requests rejected
 * @param epoch                generation counter for reset invalidation
 */
public record AdaptiveRateState(
    Instant nextFreeSlot,
    long currentIntervalNanos,
    int consecutiveSuccesses,
    int consecutiveFailures,
    int queueDepth,
    long totalAdmitted,
    long totalRejected,
    long epoch
) implements SchedulingState {

  public static AdaptiveRateState initial(long intervalNanos, Instant now) {
    return new AdaptiveRateState(now, intervalNanos, 0, 0, 0, 0, 0, 0L);
  }

  public Duration currentInterval() {
    return Duration.ofNanos(currentIntervalNanos);
  }

  public AdaptiveRateState withNextFreeSlot(Instant slot) {
    return new AdaptiveRateState(
        slot, currentIntervalNanos, consecutiveSuccesses, consecutiveFailures,
        queueDepth, totalAdmitted, totalRejected, epoch);
  }

  public AdaptiveRateState withAdmittedImmediate(Instant newNextFreeSlot) {
    return new AdaptiveRateState(
        newNextFreeSlot, currentIntervalNanos, consecutiveSuccesses, consecutiveFailures,
        queueDepth, totalAdmitted + 1, totalRejected, epoch);
  }

  public AdaptiveRateState withAdmittedDelayed(Instant newNextFreeSlot) {
    return new AdaptiveRateState(
        newNextFreeSlot, currentIntervalNanos, consecutiveSuccesses, consecutiveFailures,
        queueDepth + 1, totalAdmitted + 1, totalRejected, epoch);
  }

  public AdaptiveRateState withRequestDequeued() {
    return new AdaptiveRateState(
        nextFreeSlot, currentIntervalNanos, consecutiveSuccesses, consecutiveFailures,
        Math.max(0, queueDepth - 1), totalAdmitted, totalRejected, epoch);
  }

  public AdaptiveRateState withRequestRejected() {
    return new AdaptiveRateState(
        nextFreeSlot, currentIntervalNanos, consecutiveSuccesses, consecutiveFailures,
        queueDepth, totalAdmitted, totalRejected + 1, epoch);
  }

  public AdaptiveRateState withInterval(long newIntervalNanos, int successes, int failures) {
    return new AdaptiveRateState(
        nextFreeSlot, newIntervalNanos, successes, failures,
        queueDepth, totalAdmitted, totalRejected, epoch);
  }

  public AdaptiveRateState withNextEpoch(long initialIntervalNanos, Instant now) {
    return new AdaptiveRateState(now, initialIntervalNanos, 0, 0, 0,
        totalAdmitted, totalRejected, epoch + 1);
  }

  @Override
  public Duration projectedTailWait(Instant now) {
    if (!nextFreeSlot.isAfter(now)) return Duration.ZERO;
    return Duration.between(now, nextFreeSlot);
  }
}
