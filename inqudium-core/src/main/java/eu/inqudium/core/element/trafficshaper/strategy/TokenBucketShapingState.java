package eu.inqudium.core.element.trafficshaper.strategy;

import java.time.Duration;
import java.time.Instant;

/**
 * State for {@link TokenBucketShapingStrategy}.
 *
 * <p>Maintains a token bucket that allows bursts up to {@code burstCapacity}.
 * When tokens are available, requests proceed immediately. When exhausted,
 * requests are delayed until tokens refill, producing shaped output.
 *
 * @param availableTokens fractional token count (allows smooth sub-token refill)
 * @param lastRefillTime  when tokens were last refilled
 * @param nextFreeSlot    the scheduling timeline for queued requests
 * @param burstCapacity   maximum tokens in the bucket (set at construction)
 * @param queueDepth      requests currently waiting
 * @param totalAdmitted   total requests admitted
 * @param totalRejected   total requests rejected
 * @param epoch           generation counter for reset invalidation
 */
public record TokenBucketShapingState(
    double availableTokens,
    Instant lastRefillTime,
    Instant nextFreeSlot,
    int burstCapacity,
    int queueDepth,
    long totalAdmitted,
    long totalRejected,
    long epoch
) implements SchedulingState {

  public static TokenBucketShapingState initial(int burstCapacity, Instant now) {
    return new TokenBucketShapingState(
        burstCapacity, now, now, burstCapacity, 0, 0, 0, 0L);
  }

  public TokenBucketShapingState withTokensConsumed(double newTokens) {
    return new TokenBucketShapingState(
        newTokens, lastRefillTime, nextFreeSlot, burstCapacity,
        queueDepth, totalAdmitted, totalRejected, epoch);
  }

  public TokenBucketShapingState withRefill(double newTokens, Instant refillTime) {
    return new TokenBucketShapingState(
        newTokens, refillTime, nextFreeSlot, burstCapacity,
        queueDepth, totalAdmitted, totalRejected, epoch);
  }

  public TokenBucketShapingState withScheduledImmediate(Instant newNextFreeSlot) {
    return new TokenBucketShapingState(
        availableTokens, lastRefillTime, newNextFreeSlot, burstCapacity,
        queueDepth, totalAdmitted + 1, totalRejected, epoch);
  }

  public TokenBucketShapingState withScheduledDelayed(Instant newNextFreeSlot) {
    return new TokenBucketShapingState(
        availableTokens, lastRefillTime, newNextFreeSlot, burstCapacity,
        queueDepth + 1, totalAdmitted + 1, totalRejected, epoch);
  }

  public TokenBucketShapingState withRequestDequeued() {
    return new TokenBucketShapingState(
        availableTokens, lastRefillTime, nextFreeSlot, burstCapacity,
        Math.max(0, queueDepth - 1), totalAdmitted, totalRejected, epoch);
  }

  public TokenBucketShapingState withRequestRejected() {
    return new TokenBucketShapingState(
        availableTokens, lastRefillTime, nextFreeSlot, burstCapacity,
        queueDepth, totalAdmitted, totalRejected + 1, epoch);
  }

  public TokenBucketShapingState withNextEpoch(Instant now) {
    return new TokenBucketShapingState(
        burstCapacity, now, now, burstCapacity, 0, totalAdmitted, totalRejected, epoch + 1);
  }

  @Override
  public Duration projectedTailWait(Instant now) {
    if (!nextFreeSlot.isAfter(now)) return Duration.ZERO;
    return Duration.between(now, nextFreeSlot);
  }
}
