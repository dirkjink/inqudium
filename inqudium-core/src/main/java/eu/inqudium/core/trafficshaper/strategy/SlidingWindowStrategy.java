package eu.inqudium.core.trafficshaper.strategy;

import eu.inqudium.core.trafficshaper.ThrottleMode;
import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * Sliding window shaping strategy — rolling window with per-second buckets.
 *
 * <p>Tracks request counts in a rolling time window divided into per-second
 * buckets. The total count across all active buckets determines whether the
 * window's request limit has been reached.
 *
 * <p>Unlike {@link FixedWindowStrategy}, there is no boundary spike:
 * <pre>
 *   Fixed:   [  10 req  ][  10 req  ]  → 20 requests possible at boundary
 *   Sliding: [ 10 req rolling count ]  → always max 10 in any window-sized span
 * </pre>
 *
 * <p>When the window limit is reached, new requests are delayed until the
 * oldest bucket falls out of the window, reducing the rolling count.
 *
 * @param windowSizeSeconds the rolling window size in seconds
 * @param maxPerWindow      maximum requests allowed within the window
 */
public record SlidingWindowStrategy(
    int windowSizeSeconds,
    int maxPerWindow
) implements SchedulingStrategy<SlidingWindowState> {

  public SlidingWindowStrategy {
    if (windowSizeSeconds < 1) {
      throw new IllegalArgumentException("windowSizeSeconds must be >= 1, got " + windowSizeSeconds);
    }
    if (maxPerWindow < 1) {
      throw new IllegalArgumentException("maxPerWindow must be >= 1, got " + maxPerWindow);
    }
  }

  @Override
  public SlidingWindowState initial(TrafficShaperConfig<SlidingWindowState> config, Instant now) {
    return SlidingWindowState.initial(windowSizeSeconds, now);
  }

  @Override
  public ThrottlePermission<SlidingWindowState> schedule(
      SlidingWindowState state,
      TrafficShaperConfig<SlidingWindowState> config,
      Instant now) {

    long currentEpochSecond = now.getEpochSecond();

    // Fast-forward to clear expired buckets
    SlidingWindowState current = state.fastForward(currentEpochSecond);
    int totalInWindow = current.totalInWindow();

    if (totalInWindow < maxPerWindow) {
      // Room in the window — admit immediately
      SlidingWindowState admitted = current.withIncrementedBucket(currentEpochSecond);
      return ThrottlePermission.immediate(admitted, now);
    }

    // Window full — delay until the oldest active second falls off.
    // The earliest second that contributes to the count is
    // (currentEpochSecond - windowSizeSeconds + 1). After 1 second,
    // that bucket expires and count drops, making room.
    Duration waitDuration = Duration.ofSeconds(1);

    // Check overflow rules
    if (!config.isQueuingAllowed()
        && config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW) {
      return ThrottlePermission.rejected(current.withRequestRejected(), waitDuration);
    }
    if (config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW
        && shouldReject(current, config, waitDuration)) {
      return ThrottlePermission.rejected(current.withRequestRejected(), waitDuration);
    }

    // Admit with delay: count the request in the next second's bucket
    Instant slot = now.plusSeconds(1);
    SlidingWindowState delayed = current.withAdmittedDelayed(slot, currentEpochSecond + 1);
    return ThrottlePermission.delayed(delayed, waitDuration, slot);
  }

  @Override
  public SlidingWindowState recordExecution(SlidingWindowState state) {
    return state.withRequestDequeued();
  }

  @Override
  public SlidingWindowState reset(
      SlidingWindowState state,
      TrafficShaperConfig<SlidingWindowState> config,
      Instant now) {
    return state.withNextEpoch(now);
  }

  @Override
  public Duration estimateWait(
      SlidingWindowState state,
      TrafficShaperConfig<SlidingWindowState> config,
      Instant now) {
    SlidingWindowState current = state.fastForward(now.getEpochSecond());
    if (current.totalInWindow() < maxPerWindow) return Duration.ZERO;
    return Duration.ofSeconds(1);
  }

  @Override
  public int queueDepth(SlidingWindowState state) {
    return state.queueDepth();
  }

  @Override
  public boolean isUnboundedQueueWarning(
      SlidingWindowState state,
      TrafficShaperConfig<SlidingWindowState> config,
      Instant now) {
    return checkUnboundedWarning(state, config, now);
  }
}
