package eu.inqudium.core.trafficshaper.strategy;

import eu.inqudium.core.trafficshaper.ThrottleMode;
import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Fixed window shaping strategy.
 *
 * <p>Divides time into consecutive windows of {@code windowDuration}.
 * Within each window, up to {@code maxPerWindow} requests are admitted
 * immediately. Excess requests are delayed until the next window starts.
 *
 * <p><strong>Boundary spike:</strong> At window boundaries, up to
 * {@code 2 * maxPerWindow} requests can pass in quick succession
 * (N at the end of one window + N at the start of the next).
 * Use {@link SlidingWindowStrategy} to avoid this.
 *
 * <p>Example with maxPerWindow=3, windowDuration=1s:
 * <pre>
 *   Window 1 [0s-1s]:  |||     → 3 admitted immediately
 *   Window 1 overflow:  ..||   → 2 delayed to window 2
 *   Window 2 [1s-2s]:  |||     → 3 admitted (2 from overflow + 1 new)
 * </pre>
 *
 * @param windowDuration the size of each fixed time window
 * @param maxPerWindow   maximum requests admitted per window
 */
public record FixedWindowStrategy(
    Duration windowDuration,
    int maxPerWindow
) implements SchedulingStrategy<FixedWindowState> {

  public FixedWindowStrategy {
    Objects.requireNonNull(windowDuration, "windowDuration must not be null");
    if (windowDuration.isNegative() || windowDuration.isZero()) {
      throw new IllegalArgumentException("windowDuration must be positive");
    }
    if (maxPerWindow < 1) {
      throw new IllegalArgumentException("maxPerWindow must be >= 1, got " + maxPerWindow);
    }
  }

  @Override
  public FixedWindowState initial(TrafficShaperConfig<FixedWindowState> config, Instant now) {
    return FixedWindowState.initial(now);
  }

  @Override
  public ThrottlePermission<FixedWindowState> schedule(
      FixedWindowState state,
      TrafficShaperConfig<FixedWindowState> config,
      Instant now) {

    // Advance to the current window if time has passed
    FixedWindowState current = advanceWindow(state, now);

    if (current.requestsInWindow() < maxPerWindow) {
      // Room in this window — admit immediately
      return ThrottlePermission.immediate(current.withAdmittedImmediate(), now);
    }

    // Window full — delay until next window
    Instant nextWindowStart = current.windowStart().plus(windowDuration);
    Duration waitDuration = Duration.between(now, nextWindowStart);
    if (waitDuration.isNegative()) waitDuration = Duration.ZERO;

    // Check overflow rules
    if (!config.isQueuingAllowed()
        && config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW) {
      return ThrottlePermission.rejected(current.withRequestRejected(), waitDuration);
    }
    if (config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW
        && shouldReject(current, config, waitDuration)) {
      return ThrottlePermission.rejected(current.withRequestRejected(), waitDuration);
    }

    // Admit the request into the NEXT window's count
    FixedWindowState nextWindowState = current.withNewWindow(nextWindowStart)
        .withAdmittedDelayed(nextWindowStart);
    return ThrottlePermission.delayed(nextWindowState, waitDuration, nextWindowStart);
  }

  @Override
  public FixedWindowState recordExecution(FixedWindowState state) {
    return state.withRequestDequeued();
  }

  @Override
  public FixedWindowState reset(
      FixedWindowState state,
      TrafficShaperConfig<FixedWindowState> config,
      Instant now) {
    return state.withNextEpoch(now);
  }

  @Override
  public Duration estimateWait(
      FixedWindowState state,
      TrafficShaperConfig<FixedWindowState> config,
      Instant now) {
    FixedWindowState current = advanceWindow(state, now);
    if (current.requestsInWindow() < maxPerWindow) return Duration.ZERO;
    Instant nextWindowStart = current.windowStart().plus(windowDuration);
    Duration wait = Duration.between(now, nextWindowStart);
    return wait.isNegative() ? Duration.ZERO : wait;
  }

  @Override
  public int queueDepth(FixedWindowState state) {
    return state.queueDepth();
  }

  @Override
  public boolean isUnboundedQueueWarning(
      FixedWindowState state,
      TrafficShaperConfig<FixedWindowState> config,
      Instant now) {
    return checkUnboundedWarning(state, config, now);
  }

  private FixedWindowState advanceWindow(FixedWindowState state, Instant now) {
    if (!now.isBefore(state.windowStart().plus(windowDuration))) {
      // Current window has passed — start a new one aligned to `now`
      // (skip intermediate empty windows)
      long elapsedNanos = Duration.between(state.windowStart(), now).toNanos();
      long windowNanos = windowDuration.toNanos();
      long windowsPassed = elapsedNanos / windowNanos;
      Instant newWindowStart = state.windowStart().plusNanos(windowsPassed * windowNanos);
      return state.withNewWindow(newWindowStart);
    }
    return state;
  }
}
