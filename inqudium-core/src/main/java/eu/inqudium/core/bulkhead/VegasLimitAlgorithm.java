package eu.inqudium.core.bulkhead;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A latency-based adaptive limit algorithm inspired by TCP Vegas congestion control.
 *
 * <h2>Algorithm Explanation</h2>
 * <p>Unlike AIMD, which is reactive and waits for errors (like timeouts) to reduce the limit,
 * the Vegas algorithm is <b>proactive</b>. It continuously measures the Round-Trip-Time (RTT)
 * and attempts to detect queuing delay in the downstream service before failures occur.
 *
 * <p>It does this by calculating a "gradient":
 * <ul>
 * <li><b>No-Load RTT (Baseline):</b> The algorithm remembers the fastest recent response
 * time. This is assumed to be the physical minimum time required when the downstream
 * system has zero queued requests. The baseline slowly decays upward to prevent
 * permanent lock-in from a one-time outlier.</li>
 * <li><b>Smoothed Current RTT:</b> The algorithm calculates an Exponentially Weighted
 * Moving Average (EWMA) of recent response times to filter out sudden, random spikes.</li>
 * <li><b>The Gradient:</b> It calculates the ratio: {@code gradient = No-Load RTT / Current RTT}.
 * If the current RTT is exactly as fast as the baseline, the gradient is 1.0. If the current
 * RTT doubles (indicating a queue is forming), the gradient drops to 0.5.</li>
 * </ul>
 * The new limit is then calculated as: {@code New Limit = (Current Limit * Gradient) + Probing Factor}.
 *
 * @since 0.2.0
 */
public final class VegasLimitAlgorithm implements InqLimitAlgorithm {

  private final int minLimit;
  private final int maxLimit;
  private final double smoothingFactor;

  /**
   * FIX #2: Rate at which the no-load baseline decays toward the smoothed RTT.
   * A value of 0.01 means 1% drift per update, preventing permanent lock-in from
   * a single artificially low measurement (e.g., a cached response or GC timing artifact).
   *
   * <p>Without decay, noLoadRtt is monotonically decreasing (Math.min only). A single
   * outlier with an unrealistically low RTT would permanently poison the baseline,
   * causing the gradient to always see "congestion" and chronically over-throttle.
   */
  private final double baselineDecayFactor;

  private final AtomicReference<VegasState> state;

  /**
   * Creates a new Vegas limit algorithm instance with default baseline decay (1%).
   *
   * @param initialLimit    The starting concurrency limit.
   * @param minLimit        The absolute minimum allowed concurrency.
   * @param maxLimit        The absolute maximum allowed concurrency.
   * @param smoothingFactor A value between 0.01 and 1.0 determining how heavily new RTT
   *                        measurements impact the rolling average.
   */
  public VegasLimitAlgorithm(int initialLimit, int minLimit, int maxLimit, double smoothingFactor) {
    this(initialLimit, minLimit, maxLimit, smoothingFactor, 0.01);
  }

  /**
   * Creates a new Vegas limit algorithm instance with configurable baseline decay.
   *
   * @param initialLimit       The starting concurrency limit.
   * @param minLimit           The absolute minimum allowed concurrency.
   * @param maxLimit           The absolute maximum allowed concurrency.
   * @param smoothingFactor    EWMA smoothing factor (0.01–1.0).
   * @param baselineDecayFactor Rate at which noLoadRtt drifts toward smoothedRtt per update
   *                            (0.0 = no decay / original behavior, 0.01 = 1% drift).
   */
  public VegasLimitAlgorithm(int initialLimit, int minLimit, int maxLimit,
                             double smoothingFactor, double baselineDecayFactor) {
    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.smoothingFactor = Math.max(0.01, Math.min(1.0, smoothingFactor));
    this.baselineDecayFactor = Math.max(0.0, Math.min(0.1, baselineDecayFactor));

    double bounded = Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit));
    this.state = new AtomicReference<>(new VegasState(Long.MAX_VALUE, 0, bounded));
  }

  @Override
  public int getLimit() {
    return (int) state.get().currentLimit();
  }

  @Override
  public void update(Duration rtt, boolean isSuccess) {
    long rttNanos = rtt.toNanos();
    if (rttNanos <= 0) return;

    VegasState current;
    VegasState next;
    do {
      current = state.get();

      // 1. Maintain the Baseline with decay
      // First, take the minimum of the current baseline and the new sample.
      long candidateNoLoad = Math.min(current.noLoadRttNanos(), rttNanos);

      // FIX #2: Apply decay — slowly drift the baseline toward the smoothed RTT.
      // This prevents a single artificially low measurement from permanently poisoning
      // the gradient calculation. The decay only pulls the baseline UP (toward reality),
      // never down — Math.min above already handles downward movement.
      long newNoLoad;
      if (baselineDecayFactor > 0.0 && current.smoothedRttNanos() > 0
          && candidateNoLoad < current.smoothedRttNanos()) {
        long decayed = (long) (candidateNoLoad * (1.0 - baselineDecayFactor)
            + current.smoothedRttNanos() * baselineDecayFactor);
        // The decayed value must not exceed the smoothed RTT (the baseline should
        // always represent the "best case", not the average case).
        newNoLoad = Math.min(decayed, current.smoothedRttNanos());
      } else {
        newNoLoad = candidateNoLoad;
      }

      // 2. Calculate Smoothed Current Latency (EWMA)
      long newSmoothed = current.smoothedRttNanos() == 0
          ? rttNanos
          : (long) (current.smoothedRttNanos() * (1 - smoothingFactor) + rttNanos * smoothingFactor);

      // 3. Compute the Gradient
      double gradient = (double) newNoLoad / (double) newSmoothed;
      gradient = Math.max(0.5, Math.min(1.2, gradient));

      // 4. Calculate the new limit
      double newLimit;
      if (isSuccess) {
        newLimit = current.currentLimit() * gradient + 0.5;
      } else {
        newLimit = current.currentLimit() * 0.8;
      }
      newLimit = Math.max(minLimit, Math.min(maxLimit, newLimit));

      next = new VegasState(newNoLoad, newSmoothed, newLimit);

    } while (!state.compareAndSet(current, next));
  }

  private record VegasState(long noLoadRttNanos, long smoothedRttNanos, double currentLimit) {
  }
}
