package eu.inqudium.core.trafficshaper.strategy;

import java.time.Duration;
import java.time.Instant;

/**
 * State for {@link AimdStrategy}.
 *
 * <p>Carries a dynamic interval that follows the AIMD (Additive Increase,
 * Multiplicative Decrease) algorithm from TCP congestion control.
 *
 * <p>The rate (1/interval) produces the characteristic sawtooth pattern:
 * <pre>
 *   Rate ▲
 *        │    /|    /|    /|
 *        │   / |   / |   / |
 *        │  /  |  /  |  /  |
 *        │ /   | /   | /   |
 *        │/    |/    |/    |
 *        └─────────────────► Time
 *          ↑    ↑
 *        failures cause multiplicative decrease
 * </pre>
 *
 * @param nextFreeSlot         scheduling timeline
 * @param currentIntervalNanos the dynamically adjusted interval
 * @param queueDepth           requests currently waiting
 * @param totalAdmitted        total requests admitted
 * @param totalRejected        total requests rejected
 * @param epoch                generation counter
 */
public record AimdState(
    Instant nextFreeSlot,
    long currentIntervalNanos,
    int queueDepth,
    long totalAdmitted,
    long totalRejected,
    long epoch
) implements SchedulingState {

  public static AimdState initial(long intervalNanos, Instant now) {
    return new AimdState(now, intervalNanos, 0, 0, 0, 0L);
  }

  public Duration currentInterval() {
    return Duration.ofNanos(currentIntervalNanos);
  }

  public AimdState withNextFreeSlot(Instant slot) {
    return new AimdState(slot, currentIntervalNanos, queueDepth,
        totalAdmitted, totalRejected, epoch);
  }

  public AimdState withAdmittedImmediate(Instant newNextFreeSlot) {
    return new AimdState(newNextFreeSlot, currentIntervalNanos, queueDepth,
        totalAdmitted + 1, totalRejected, epoch);
  }

  public AimdState withAdmittedDelayed(Instant newNextFreeSlot) {
    return new AimdState(newNextFreeSlot, currentIntervalNanos, queueDepth + 1,
        totalAdmitted + 1, totalRejected, epoch);
  }

  public AimdState withRequestDequeued() {
    return new AimdState(nextFreeSlot, currentIntervalNanos,
        Math.max(0, queueDepth - 1), totalAdmitted, totalRejected, epoch);
  }

  public AimdState withRequestRejected() {
    return new AimdState(nextFreeSlot, currentIntervalNanos, queueDepth,
        totalAdmitted, totalRejected + 1, epoch);
  }

  public AimdState withInterval(long newIntervalNanos) {
    return new AimdState(nextFreeSlot, newIntervalNanos, queueDepth,
        totalAdmitted, totalRejected, epoch);
  }

  public AimdState withNextEpoch(long initialIntervalNanos, Instant now) {
    return new AimdState(now, initialIntervalNanos, 0,
        totalAdmitted, totalRejected, epoch + 1);
  }

  @Override
  public Duration projectedTailWait(Instant now) {
    if (!nextFreeSlot.isAfter(now)) return Duration.ZERO;
    return Duration.between(now, nextFreeSlot);
  }
}
