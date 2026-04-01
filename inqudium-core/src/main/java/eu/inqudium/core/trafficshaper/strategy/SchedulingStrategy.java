package eu.inqudium.core.trafficshaper.strategy;

import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * Strategy interface for traffic shaping algorithms.
 *
 * <p>Implementations define <em>how</em> incoming requests are scheduled
 * across time to produce smooth output traffic. The {@link eu.inqudium.imperative.trafficshaper.ImperativeTrafficShaper}
 * delegates all scheduling decisions to this interface.
 *
 * <p><strong>Immutability contract:</strong> All implementations must treat the
 * state parameter as immutable and return a new state instance for every
 * mutating operation. The imperative wrapper relies on this for its CAS-based
 * thread safety model.
 *
 * <p>The default implementation is {@link LeakyBucketStrategy}, which assigns
 * evenly-spaced time slots. Other possible strategies include:
 * <ul>
 *   <li><strong>Token bucket shaping</strong> — allows controlled bursts up to
 *       a capacity, then shapes the remainder</li>
 *   <li><strong>Weighted fair queuing</strong> — assigns slots based on request
 *       priority or weight</li>
 *   <li><strong>Adaptive rate shaping</strong> — dynamically adjusts the interval
 *       based on downstream backpressure signals</li>
 *   <li><strong>Sliding window shaping</strong> — smooths traffic within a
 *       rolling time window rather than per-slot</li>
 * </ul>
 *
 * @param <S> the strategy-specific state type
 */
public interface SchedulingStrategy<S extends SchedulingState> {

  /**
   * Creates the initial state for this strategy.
   *
   * @param config the traffic shaper configuration
   * @param now    the creation time
   * @return a fresh state with the first slot immediately available
   */
  S initial(TrafficShaperConfig<S> config, Instant now);

  /**
   * Schedules a request and returns the throttling decision.
   *
   * <p>This is the central function. It evaluates whether the request
   * can be admitted (immediately or after a delay) or must be rejected,
   * and returns the updated state reflecting the scheduling decision.
   *
   * @param state  the current scheduling state
   * @param config the traffic shaper configuration
   * @param now    the arrival time of the request
   * @return a throttle permission with the updated state
   */
  ThrottlePermission<S> schedule(S state, TrafficShaperConfig<S> config, Instant now);

  /**
   * Records that a previously queued request has left the queue and
   * started executing. Typically decrements the queue depth.
   *
   * @param state the current state
   * @return the updated state with the request dequeued
   */
  S recordExecution(S state);

  /**
   * Resets the strategy to its initial state with an incremented epoch.
   * All pending reservations are invalidated — parked threads detect the
   * epoch change and re-acquire their slot.
   *
   * @param state  the current state (epoch is read and incremented)
   * @param config the traffic shaper configuration
   * @param now    the reset time
   * @return a fresh state with incremented epoch
   */
  S reset(S state, TrafficShaperConfig<S> config, Instant now);

  /**
   * Estimates the wait time for a request arriving at the given instant.
   * Does <strong>not</strong> modify the state.
   *
   * @param state  the current state
   * @param config the traffic shaper configuration
   * @param now    the hypothetical arrival time
   * @return the estimated wait duration
   */
  Duration estimateWait(S state, TrafficShaperConfig<S> config, Instant now);

  /**
   * Returns the current queue depth from the state.
   */
  int queueDepth(S state);

  /**
   * Checks whether the unbounded queue has grown beyond the warning threshold.
   *
   * @param state  the current state
   * @param config the configuration (contains the warning threshold)
   * @param now    the current time
   * @return true if a warning should be emitted
   */
  boolean isUnboundedQueueWarning(S state, TrafficShaperConfig<S> config, Instant now);
}
