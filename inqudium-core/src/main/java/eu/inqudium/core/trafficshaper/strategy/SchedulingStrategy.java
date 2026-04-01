package eu.inqudium.core.trafficshaper.strategy;

import eu.inqudium.core.trafficshaper.ThrottleMode;
import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * Strategy interface for traffic shaping algorithms.
 *
 * <p>Implementations define <em>how</em> incoming requests are scheduled
 * across time to produce smooth output traffic.
 *
 * <p><strong>Immutability contract:</strong> All implementations must treat the
 * state parameter as immutable and return a new state instance for every
 * mutating operation.
 *
 * <h2>Built-in strategies</h2>
 * <table>
 *   <tr><th>Strategy</th><th>Burst</th><th>Adaptive</th><th>Use case</th></tr>
 *   <tr><td>{@link LeakyBucketStrategy}</td><td>No</td><td>No</td><td>Strict even spacing</td></tr>
 *   <tr><td>{@link TokenBucketShapingStrategy}</td><td>Yes</td><td>No</td><td>Allow bursts, then shape</td></tr>
 *   <tr><td>{@link FixedWindowStrategy}</td><td>Yes</td><td>No</td><td>Simple window-based limit</td></tr>
 *   <tr><td>{@link SlidingWindowStrategy}</td><td>Yes</td><td>No</td><td>Rolling window, no boundary spike</td></tr>
 *   <tr><td>{@link AdaptiveRateStrategy}</td><td>No</td><td>Yes</td><td>Downstream backpressure</td></tr>
 *   <tr><td>{@link AimdStrategy}</td><td>No</td><td>Yes</td><td>TCP-style congestion control</td></tr>
 * </table>
 *
 * @param <S> the strategy-specific state type
 */
public interface SchedulingStrategy<S extends SchedulingState> {

  S initial(TrafficShaperConfig<S> config, Instant now);

  ThrottlePermission<S> schedule(S state, TrafficShaperConfig<S> config, Instant now);

  S recordExecution(S state);

  S reset(S state, TrafficShaperConfig<S> config, Instant now);

  Duration estimateWait(S state, TrafficShaperConfig<S> config, Instant now);

  int queueDepth(S state);

  boolean isUnboundedQueueWarning(S state, TrafficShaperConfig<S> config, Instant now);

  /**
   * Records that a shaped request completed successfully.
   *
   * <p>Adaptive strategies (e.g., {@link AdaptiveRateStrategy}, {@link AimdStrategy})
   * override this to increase the throughput rate. Non-adaptive strategies return
   * the state unchanged.
   *
   * @param state the current state
   * @return the updated state (possibly with adjusted rate)
   */
  default S recordSuccess(S state) {
    return state;
  }

  /**
   * Records that a shaped request failed.
   *
   * <p>Adaptive strategies override this to decrease the throughput rate.
   * Non-adaptive strategies return the state unchanged.
   *
   * @param state the current state
   * @return the updated state (possibly with adjusted rate)
   */
  default S recordFailure(S state) {
    return state;
  }

  // ======================== Shared overflow check ========================

  /**
   * Shared helper for overflow detection used by multiple strategies.
   * Checks queue depth and wait duration limits in SHAPE_AND_REJECT_OVERFLOW mode.
   */
  default boolean shouldReject(S state, TrafficShaperConfig<S> config, Duration waitDuration) {
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
   * Shared helper for unbounded queue warning detection.
   */
  default boolean checkUnboundedWarning(S state, TrafficShaperConfig<S> config, Instant now) {
    if (config.throttleMode() != ThrottleMode.SHAPE_UNBOUNDED) return false;
    if (config.unboundedWarnAfter() == null) return false;
    return state.projectedTailWait(now).compareTo(config.unboundedWarnAfter()) > 0;
  }
}
