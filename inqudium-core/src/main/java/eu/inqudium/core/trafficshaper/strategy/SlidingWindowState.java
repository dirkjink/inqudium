package eu.inqudium.core.trafficshaper.strategy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * State for {@link SlidingWindowStrategy}.
 *
 * <p>Uses per-second buckets (like {@code TimeBasedErrorRateMetrics}) to track
 * request counts in a rolling time window. The total across all active buckets
 * determines whether the window's request limit has been reached.
 *
 * <p>Defensive array copies are made on construction and access to maintain
 * immutability (same pattern as the circuit breaker's metrics).
 *
 * @param buckets                request counts per second-bucket
 * @param windowSizeSeconds      number of buckets in the window
 * @param lastUpdatedEpochSecond the epoch-second when buckets were last updated
 * @param nextFreeSlot           scheduling timeline for delayed requests
 * @param queueDepth             requests currently waiting
 * @param totalAdmitted          total requests admitted
 * @param totalRejected          total requests rejected
 * @param epoch                  generation counter for reset invalidation
 */
public record SlidingWindowState(
    int[] buckets,
    int windowSizeSeconds,
    long lastUpdatedEpochSecond,
    Instant nextFreeSlot,
    int queueDepth,
    long totalAdmitted,
    long totalRejected,
    long epoch
) implements SchedulingState {

  // Defensive copy on construction
  public SlidingWindowState {
    buckets = Arrays.copyOf(buckets, buckets.length);
  }

  public static SlidingWindowState initial(int windowSizeSeconds, Instant now) {
    return new SlidingWindowState(
        new int[windowSizeSeconds], windowSizeSeconds,
        now.getEpochSecond(), now, 0, 0, 0, 0L);
  }

  // Defensive copy on access
  @Override
  public int[] buckets() {
    return Arrays.copyOf(buckets, buckets.length);
  }

  /**
   * Returns the total request count across all active buckets.
   */
  public int totalInWindow() {
    return Arrays.stream(buckets).sum();
  }

  public SlidingWindowState withIncrementedBucket(long epochSecond) {
    int[] newBuckets = Arrays.copyOf(buckets, windowSizeSeconds);
    int idx = bucketIndex(epochSecond);
    newBuckets[idx]++;
    long newLast = Math.max(lastUpdatedEpochSecond, epochSecond);
    return new SlidingWindowState(
        newBuckets, windowSizeSeconds, newLast, nextFreeSlot,
        queueDepth, totalAdmitted + 1, totalRejected, epoch);
  }

  public SlidingWindowState withAdmittedDelayed(Instant newNextFreeSlot, long epochSecond) {
    int[] newBuckets = Arrays.copyOf(buckets, windowSizeSeconds);
    int idx = bucketIndex(epochSecond);
    newBuckets[idx]++;
    long newLast = Math.max(lastUpdatedEpochSecond, epochSecond);
    return new SlidingWindowState(
        newBuckets, windowSizeSeconds, newLast, newNextFreeSlot,
        queueDepth + 1, totalAdmitted + 1, totalRejected, epoch);
  }

  public SlidingWindowState withRequestDequeued() {
    return new SlidingWindowState(
        buckets, windowSizeSeconds, lastUpdatedEpochSecond, nextFreeSlot,
        Math.max(0, queueDepth - 1), totalAdmitted, totalRejected, epoch);
  }

  public SlidingWindowState withRequestRejected() {
    return new SlidingWindowState(
        buckets, windowSizeSeconds, lastUpdatedEpochSecond, nextFreeSlot,
        queueDepth, totalAdmitted, totalRejected + 1, epoch);
  }

  public SlidingWindowState withNextEpoch(Instant now) {
    return new SlidingWindowState(
        new int[windowSizeSeconds], windowSizeSeconds,
        now.getEpochSecond(), now, 0, totalAdmitted, totalRejected, epoch + 1);
  }

  /**
   * Fast-forwards the buckets to clear expired entries.
   */
  public SlidingWindowState fastForward(long currentEpochSecond) {
    if (currentEpochSecond <= lastUpdatedEpochSecond) return this;

    long delta = currentEpochSecond - lastUpdatedEpochSecond;
    if (delta >= windowSizeSeconds) {
      return new SlidingWindowState(
          new int[windowSizeSeconds], windowSizeSeconds,
          currentEpochSecond, nextFreeSlot, queueDepth,
          totalAdmitted, totalRejected, epoch);
    }

    int[] newBuckets = Arrays.copyOf(buckets, windowSizeSeconds);
    for (long i = 1; i <= delta; i++) {
      int idx = bucketIndex(lastUpdatedEpochSecond + i);
      newBuckets[idx] = 0;
    }
    return new SlidingWindowState(
        newBuckets, windowSizeSeconds, currentEpochSecond, nextFreeSlot,
        queueDepth, totalAdmitted, totalRejected, epoch);
  }

  private int bucketIndex(long epochSecond) {
    int idx = (int) (epochSecond % windowSizeSeconds);
    return idx < 0 ? idx + windowSizeSeconds : idx;
  }

  @Override
  public Duration projectedTailWait(Instant now) {
    if (!nextFreeSlot.isAfter(now)) return Duration.ZERO;
    return Duration.between(now, nextFreeSlot);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SlidingWindowState that)) return false;
    return windowSizeSeconds == that.windowSizeSeconds
        && lastUpdatedEpochSecond == that.lastUpdatedEpochSecond
        && queueDepth == that.queueDepth
        && totalAdmitted == that.totalAdmitted
        && totalRejected == that.totalRejected
        && epoch == that.epoch
        && Objects.equals(nextFreeSlot, that.nextFreeSlot)
        && Arrays.equals(buckets, that.buckets);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(windowSizeSeconds, lastUpdatedEpochSecond,
        nextFreeSlot, queueDepth, totalAdmitted, totalRejected, epoch);
    return 31 * result + Arrays.hashCode(buckets);
  }
}
