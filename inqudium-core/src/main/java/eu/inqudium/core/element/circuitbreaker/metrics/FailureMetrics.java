package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.function.LongFunction;

/**
 * Strategy interface for tracking failures and determining if a circuit breaker should open.
 *
 * <p>This is the central abstraction of the circuit breaker's failure detection subsystem.
 * Each implementation encodes a different algorithm for deciding when failures have become
 * frequent or severe enough to warrant tripping the circuit (e.g., consecutive failures,
 * sliding windows, exponentially weighted moving averages, leaky buckets, etc.).
 *
 * <h2>Immutability Contract</h2>
 * <p>All implementations <strong>must be immutable</strong>. Every mutating operation
 * (recording an outcome, resetting) returns a <em>new</em> instance rather than modifying
 * internal state. This design enables a functional core architecture where state transitions
 * are explicit, side-effect-free, and trivially testable.
 *
 * <h2>Time Model</h2>
 * <p>All time parameters are expressed as <strong>nanoseconds</strong> obtained from
 * {@link eu.inqudium.core.time.InqNanoTimeSource#now()}. By injecting a controllable
 * time source, tests can simulate the passage of time deterministically without
 * {@code Thread.sleep()} calls (see ADR-016). Implementations that do not depend on
 * wall-clock time (e.g., {@link ConsecutiveFailuresMetrics}) may safely ignore the
 * {@code nowNanos} parameter, but still accept it to satisfy the uniform interface.
 *
 * <h2>Typical Lifecycle</h2>
 * <ol>
 *   <li>Create an initial instance via a static factory method on the concrete class.</li>
 *   <li>On every downstream call result, invoke {@link #recordSuccess(long)} or
 *       {@link #recordFailure(long)} and replace the held reference with the returned instance.</li>
 *   <li>After each recording, call {@link #isThresholdReached(long)} to decide whether to
 *       open the circuit.</li>
 *   <li>When the circuit transitions back to CLOSED, call {@link #reset(long)} to obtain
 *       a clean-slate instance.</li>
 * </ol>
 */
public interface FailureMetrics {

  /**
   * Records a successful downstream call and returns the updated metrics state.
   *
   * <p>Depending on the algorithm, a success may:
   * <ul>
   *   <li>Reset an internal counter (e.g., {@link ConsecutiveFailuresMetrics}).</li>
   *   <li>Heal exactly one prior failure (e.g., {@link GradualDecayMetrics}).</li>
   *   <li>Push a "success" sample into a sliding window or EWMA calculation.</li>
   *   <li>Simply trigger time-based decay without adding to a failure bucket.</li>
   * </ul>
   *
   * @param nowNanos the current timestamp in nanoseconds from the injected time source
   * @return a new {@code FailureMetrics} instance reflecting the recorded success
   */
  FailureMetrics recordSuccess(long nowNanos);

  /**
   * Records a failed downstream call and returns the updated metrics state.
   *
   * <p>A failure always moves the metrics closer to the threshold. The exact effect
   * depends on the algorithm (incrementing a counter, adding a sample of 1.0 to an EWMA,
   * filling a leaky bucket, etc.).
   *
   * @param nowNanos the current timestamp in nanoseconds from the injected time source
   * @return a new {@code FailureMetrics} instance reflecting the recorded failure
   */
  FailureMetrics recordFailure(long nowNanos);

  /**
   * Evaluates whether the failure threshold has been reached at the given point in time.
   *
   * <p>This method is a <strong>pure query</strong> — it does not mutate state. For
   * time-sensitive algorithms (leaky bucket, time-based windows, continuous EWMA) it
   * internally projects the current state forward to {@code nowNanos} before comparing
   * against the configured threshold.
   *
   * <p>Some implementations enforce a <em>minimum number of calls</em> before the
   * threshold can be reached, preventing premature tripping on insufficient data.
   *
   * @param nowNanos the current timestamp in nanoseconds from the injected time source
   * @return {@code true} if the circuit should open; {@code false} otherwise
   */
  boolean isThresholdReached(long nowNanos);

  /**
   * Resets the metrics to their pristine initial state.
   *
   * <p>Called when the circuit breaker transitions back to the CLOSED state after a
   * successful half-open probe. The returned instance retains the original configuration
   * (thresholds, window sizes, etc.) but clears all accumulated failure data.
   *
   * @param nowNanos the current timestamp in nanoseconds — used by time-aware
   *                 implementations to anchor the new window or decay baseline
   * @return a fresh {@code FailureMetrics} instance with zeroed-out counters/windows
   */
  FailureMetrics reset(long nowNanos);

  /**
   * Returns a human-readable, detailed explanation of why the threshold was reached.
   *
   * <p>This message is intended for structured logging and DevOps dashboards. It should
   * include the concrete numbers that led to the trip decision so that operators can
   * immediately understand the situation without consulting additional metrics.
   *
   * <p><strong>Example output:</strong>
   * <pre>
   *   "Sliding window threshold reached: Found 8 failures in the last 10 calls (Threshold: 5)."
   * </pre>
   *
   * @param nowNanos the current timestamp in nanoseconds, used to compute time-dependent
   *                 values (e.g., decayed rates) for inclusion in the message
   * @return a non-null descriptive string; meaningful even if the threshold is not currently reached
   */
  String getTripReason(long nowNanos);

  /**
   * Returns a factory function that produces a fresh instance of this metrics strategy.
   *
   * <p>The returned {@link LongFunction} accepts a nanosecond timestamp and creates a new,
   * pristine {@link FailureMetrics} instance with the same configuration (thresholds, window
   * sizes, smoothing factors, etc.) but zeroed-out counters and windows. The timestamp is
   * used by time-aware implementations to anchor decay baselines or window boundaries.
   *
   * <p>This factory is invoked by the circuit breaker on creation and on every reset
   * (transition back to CLOSED), ensuring that the new metrics instance starts from a
   * clean slate while preserving the operator's chosen algorithm and parameters.
   *
   * @return a {@link LongFunction} that accepts a nanosecond timestamp and produces a
   *         fresh {@link FailureMetrics} instance with identical configuration
   */
  LongFunction<FailureMetrics> metricsFactory();}
