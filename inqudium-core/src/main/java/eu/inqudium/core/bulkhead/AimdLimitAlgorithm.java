package eu.inqudium.core.bulkhead;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe implementation of the Additive Increase, Multiplicative Decrease (AIMD) algorithm.
 *
 * <p><b>Algorithm Explanation:</b>
 * The AIMD algorithm acts as a dynamic feedback loop for concurrency limits, originally
 * made famous by its use in TCP congestion control. It continuously adjusts the allowed
 * concurrency based on the success or failure of ongoing requests:
 * <ul>
 * <li><b>Additive Increase (Probing):</b> On each successful call, the limit is increased.
 * Two strategies are available:
 *   <ul>
 *     <li><b>Fixed (default):</b> {@code +1} per success — simple, predictable, suitable for
 *         low-to-moderate throughput systems.</li>
 *     <li><b>Windowed (opt-in):</b> {@code +1/currentLimit} per success — matches classic TCP
 *         AIMD. Over a full window of {@code currentLimit} successes, the net increase is
 *         exactly +1, regardless of transaction rate. Prevents runaway growth at high RPS.</li>
 *   </ul>
 * </li>
 * <li><b>Multiplicative Decrease (Protecting):</b> When failures are detected, the limit is
 * multiplied by a fractional factor (e.g., 0.5). With EWMA smoothing enabled, transient
 * single failures are absorbed without capacity loss — only sustained error rates above
 * a configurable threshold trigger the decrease.</li>
 * </ul>
 *
 * <h2>Configuration Modes</h2>
 *
 * <h3>Classic Mode (4-parameter constructor)</h3>
 * <p>Backward-compatible with the original implementation. Each success adds +1, each failure
 * immediately triggers multiplicative decrease. Simple and predictable, but prone to
 * oscillation at high throughput.
 *
 * <h3>Stabilized Mode (full constructor)</h3>
 * <p>Enables windowed additive increase and EWMA error rate smoothing. Suitable for
 * high-throughput production systems where transient errors should not cause capacity drops
 * and the sawtooth amplitude should be independent of RPS.
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable state is bundled into an immutable {@link AimdState} record and managed
 * via {@link AtomicReference#compareAndSet}, identical to the pattern used by
 * {@link VegasLimitAlgorithm}. This guarantees consistent snapshots without locking.
 *
 * @since 0.2.0
 */
public final class AimdLimitAlgorithm implements InqLimitAlgorithm {

  private final int minLimit;
  private final int maxLimit;
  private final double backoffRatio;
  private final double smoothingFactor;
  private final double errorRateThreshold;
  private final boolean windowedIncrease;

  private final AtomicReference<AimdState> state;

  /**
   * Creates a new AIMD algorithm with classic behavior (backward-compatible).
   *
   * <p>Each success adds {@code +1} to the limit. Each failure immediately triggers
   * multiplicative decrease. No EWMA smoothing is applied — this preserves the exact
   * behavior of the original implementation.
   *
   * @param initialLimit The starting concurrency limit before any feedback is received.
   * @param minLimit     The absolute minimum limit (must be >= 1) to ensure the system never
   *                     completely locks up and can always send at least some probe requests.
   * @param maxLimit     The absolute upper bound to prevent infinite scaling.
   * @param backoffRatio The multiplier used during the decrease phase (e.g., 0.5 for halving,
   *                     0.8 for a milder 20% reduction). Must be strictly between 0.1 and 0.9.
   */
  public AimdLimitAlgorithm(int initialLimit, int minLimit, int maxLimit, double backoffRatio) {
    // smoothingFactor=1.0: each sample fully overwrites the error rate (no smoothing)
    // errorRateThreshold=0.0: any failure immediately triggers decrease
    // windowedIncrease=false: +1 per success (classic behavior)
    this(initialLimit, minLimit, maxLimit, backoffRatio, 1.0, 0.0, false);
  }

  /**
   * Creates a new AIMD algorithm with full control over increase and decrease behavior.
   *
   * <p><b>Recommended production settings:</b>
   * <pre>{@code
   * new AimdLimitAlgorithm(
   *     initialLimit, minLimit, maxLimit,
   *     0.5,    // backoffRatio: halve on sustained errors
   *     0.1,    // smoothingFactor: slow EWMA, filters transients
   *     0.1,    // errorRateThreshold: 10% sustained error rate before backing off
   *     true    // windowedIncrease: TCP-style +1/cwnd per success
   * );
   * }</pre>
   *
   * @param initialLimit       The starting concurrency limit.
   * @param minLimit           The absolute minimum limit (must be >= 1).
   * @param maxLimit           The absolute upper bound.
   * @param backoffRatio       The decrease multiplier (0.1–0.9).
   * @param smoothingFactor    EWMA alpha for error rate (0.01–1.0). Lower = smoother.
   *                           A value of 1.0 disables smoothing (each sample fully overwrites).
   * @param errorRateThreshold The smoothed error rate must exceed this value (0.0–1.0) to
   *                           trigger multiplicative decrease. A value of 0.0 means every
   *                           failure triggers decrease (classic AIMD behavior).
   * @param windowedIncrease   If {@code true}, each success adds {@code 1/currentLimit}
   *                           (TCP-style windowed increase). If {@code false}, each success
   *                           adds {@code +1} (classic behavior).
   */
  public AimdLimitAlgorithm(int initialLimit, int minLimit, int maxLimit,
                            double backoffRatio, double smoothingFactor,
                            double errorRateThreshold, boolean windowedIncrease) {
    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.backoffRatio = Math.max(0.1, Math.min(0.9, backoffRatio));
    this.smoothingFactor = Math.max(0.01, Math.min(1.0, smoothingFactor));
    this.errorRateThreshold = Math.max(0.0, Math.min(1.0, errorRateThreshold));
    this.windowedIncrease = windowedIncrease;

    double bounded = Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit));
    this.state = new AtomicReference<>(new AimdState(bounded, 0.0));
  }

  @Override
  public int getLimit() {
    return (int) state.get().currentLimit();
  }

  /**
   * Updates the algorithm state using a lock-free CAS loop.
   *
   * <p>FIX #10: Zero/negative RTT values are silently ignored, consistent with
   * {@link VegasLimitAlgorithm} behavior.
   *
   * <p>The update performs two steps atomically:
   * <ol>
   *   <li><b>EWMA error rate update:</b> The smoothed error rate is adjusted toward
   *       0.0 (success) or 1.0 (failure) by the configured smoothing factor.</li>
   *   <li><b>Limit adjustment:</b>
   *     <ul>
   *       <li><b>Success:</b> Fixed ({@code +1}) or windowed ({@code +1/currentLimit})
   *           additive increase, depending on the configured strategy.</li>
   *       <li><b>Failure + error rate above threshold:</b> Multiplicative decrease
   *           ({@code limit * backoffRatio}).</li>
   *       <li><b>Failure + error rate below threshold:</b> No limit change.
   *           The error is recorded in the EWMA but does not yet warrant a reduction.</li>
   *     </ul>
   *   </li>
   * </ol>
   */
  @Override
  public void update(Duration rtt, boolean isSuccess) {
    if (rtt.toNanos() <= 0) {
      return;
    }

    AimdState current;
    AimdState next;
    do {
      current = state.get();

      // 1. Update EWMA error rate
      // Success contributes 0.0, failure contributes 1.0 to the moving average.
      // With smoothingFactor=1.0 (default for classic mode), each sample fully
      // overwrites: errorRate becomes 0.0 on success, 1.0 on failure — matching
      // the original per-call decrease behavior exactly.
      double sample = isSuccess ? 0.0 : 1.0;
      double newErrorRate = current.smoothedErrorRate() * (1.0 - smoothingFactor)
          + sample * smoothingFactor;

      // 2. Calculate new limit
      double newLimit;
      if (isSuccess) {
        if (windowedIncrease) {
          // Windowed Additive Increase: +1/currentLimit per success.
          // Over one full window of `currentLimit` consecutive successes, the net
          // increase is exactly +1 — matching classic TCP AIMD behavior.
          // At limit=100, each success adds 0.01; at limit=1, adds 1.0.
          newLimit = current.currentLimit() + 1.0 / current.currentLimit();
        } else {
          // Fixed Additive Increase: +1 per success (classic behavior).
          // Simple and predictable, but the limit growth rate is proportional to
          // transaction volume rather than to the current limit.
          newLimit = current.currentLimit() + 1.0;
        }
      } else if (newErrorRate > errorRateThreshold) {
        // Multiplicative Decrease: sustained error rate exceeds threshold.
        // With errorRateThreshold=0.0 (default for classic mode), any failure
        // with errorRate > 0.0 triggers this — matching the original behavior.
        newLimit = current.currentLimit() * backoffRatio;
      } else {
        // Transient failure: record in EWMA but don't reduce capacity yet.
        // Only reachable when errorRateThreshold > 0.0 (stabilized mode).
        newLimit = current.currentLimit();
      }

      newLimit = Math.max(minLimit, Math.min(maxLimit, newLimit));
      next = new AimdState(newLimit, newErrorRate);

    } while (!state.compareAndSet(current, next));
  }

  /**
   * Immutable snapshot of the algorithm's internal state.
   * Bundling both fields into a single record enables atomic CAS transitions,
   * preventing inconsistent reads between the limit and the error rate.
   *
   * @param currentLimit      the current concurrency limit (double for fractional increments)
   * @param smoothedErrorRate the EWMA-smoothed error rate (0.0 = all successes, 1.0 = all failures)
   */
  private record AimdState(double currentLimit, double smoothedErrorRate) {
  }
}
