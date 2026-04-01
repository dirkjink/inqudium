package eu.inqudium.core.trafficshaper;

import eu.inqudium.core.trafficshaper.strategy.SchedulingState;

import java.time.Duration;
import java.time.Instant;

/**
 * Result of scheduling a request through the traffic shaper.
 *
 * <p>Unlike a rate limiter which is binary (permit/reject), the traffic
 * shaper has three outcomes:
 * <ul>
 *   <li><strong>Immediate</strong>: the request may proceed now (zero wait).</li>
 *   <li><strong>Delayed</strong>: the request is admitted but must wait for its slot.</li>
 *   <li><strong>Rejected</strong>: the queue is full or wait would exceed the limit.</li>
 * </ul>
 *
 * @param state         the updated scheduling state (with this request's scheduling applied)
 * @param admitted      whether the request was admitted (immediate or delayed)
 * @param waitDuration  how long the caller must wait (zero if immediate or rejected)
 * @param scheduledSlot the absolute instant at which the request may proceed
 *                      ({@code null} if rejected)
 * @param <S>           the strategy-specific state type
 */
public record ThrottlePermission<S extends SchedulingState>(
    S state,
    boolean admitted,
    Duration waitDuration,
    Instant scheduledSlot
) {

  /**
   * The request may proceed immediately — no wait required.
   */
  public static <S extends SchedulingState> ThrottlePermission<S> immediate(S state, Instant slot) {
    return new ThrottlePermission<>(state, true, Duration.ZERO, slot);
  }

  /**
   * The request is admitted but must wait for its scheduled slot.
   */
  public static <S extends SchedulingState> ThrottlePermission<S> delayed(
      S state, Duration waitDuration, Instant slot) {
    return new ThrottlePermission<>(state, true, waitDuration, slot);
  }

  /**
   * The request is rejected — queue is full or wait would exceed the limit.
   */
  public static <S extends SchedulingState> ThrottlePermission<S> rejected(
      S state, Duration wouldHaveWaited) {
    return new ThrottlePermission<>(state, false, wouldHaveWaited, null);
  }

  /**
   * Returns {@code true} if the request requires a non-zero wait.
   */
  public boolean requiresWait() {
    return admitted && !waitDuration.isZero();
  }
}
