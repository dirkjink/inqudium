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
 * <li><b>No-Load RTT (Baseline):</b> The algorithm remembers the absolute fastest response
 * time it has ever seen. This is assumed to be the physical minimum time required when
 * the downstream system has zero queued requests.</li>
 * <li><b>Smoothed Current RTT:</b> The algorithm calculates an Exponentially Weighted
 * Moving Average (EWMA) of recent response times to filter out sudden, random spikes.</li>
 * <li><b>The Gradient:</b> It calculates the ratio: {@code gradient = No-Load RTT / Current RTT}.
 * If the current RTT is exactly as fast as the baseline, the gradient is 1.0. If the current
 * RTT doubles (indicating a queue is forming), the gradient drops to 0.5.</li>
 * </ul>
 * The new limit is then calculated as: {@code New Limit = (Current Limit * Gradient) + Probing Factor}.
 *
 * <h2>Advantages</h2>
 * <ul>
 * <li><b>Ultra-Low Latency:</b> Because it sheds load the moment queues begin to form, it
 * keeps the downstream latency extremely stable and low.</li>
 * <li><b>Proactive Protection:</b> It prevents the downstream system from ever reaching
 * a catastrophic failure state, avoiding timeouts entirely.</li>
 * <li><b>Smooth Adjustments:</b> It avoids the violent "sawtooth" drops of AIMD, providing
 * a much smoother, mathematically elegant traffic flow.</li>
 * </ul>
 *
 * <h2>Disadvantages</h2>
 * <ul>
 * <li><b>The Latecomer Problem:</b> If the algorithm is initialized while the downstream
 * service is already under heavy load, it will record a slow "No-Load RTT" baseline and
 * fail to throttle correctly.</li>
 * <li><b>Jitter Sensitivity:</b> It struggles with workloads that have naturally high
 * latency variance (e.g., a database where a simple SELECT takes 2ms, but a complex JOIN
 * takes 200ms). The algorithm might falsely interpret the 200ms query as congestion.</li>
 * <li><b>Over-Throttling:</b> In highly variable network environments, it tends to be
 * overly pessimistic and might throttle more than necessary.</li>
 * </ul>
 *
 * @since 0.2.0
 */
public final class VegasLimitAlgorithm implements InqLimitAlgorithm {

  private final int minLimit;
  private final int maxLimit;
  private final double smoothingFactor;

  /**
   * All mutable algorithm state is bundled into a single immutable record and managed
   * via {@link AtomicReference#compareAndSet}.
   * This guarantees that every {@link #update} reads and writes a consistent snapshot
   * of (noLoadRtt, smoothedRtt, currentLimit) without any blocking — purely through
   * a CAS retry loop.
   *
   * <p>The original implementation used three independent atomic variables. Concurrent
   * threads could interleave between individual reads, causing the gradient calculation
   * to mix a noLoadRtt from one thread with a smoothedRtt from another — producing
   * inconsistent and potentially erratic limit adjustments.
   *
   * <p>Using {@code synchronized} was rejected because core implementations must never
   * block. The CAS loop is wait-free in practice (contention is bounded by the number
   * of concurrent callers, and the compute step is pure arithmetic with no I/O).
   */
  private final AtomicReference<VegasState> state;

  /**
   * Creates a new Vegas limit algorithm instance.
   *
   * @param initialLimit    The starting concurrency limit.
   * @param minLimit        The absolute minimum allowed concurrency.
   * @param maxLimit        The absolute maximum allowed concurrency.
   * @param smoothingFactor A value between 0.01 and 1.0 determining how heavily new RTT
   *                        measurements impact the rolling average. Lower values (e.g., 0.2)
   *                        make the algorithm less sensitive to sudden random spikes.
   */
  public VegasLimitAlgorithm(int initialLimit, int minLimit, int maxLimit, double smoothingFactor) {
    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.smoothingFactor = Math.max(0.01, Math.min(1.0, smoothingFactor));

    double bounded = Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit));
    this.state = new AtomicReference<>(new VegasState(Long.MAX_VALUE, 0, bounded));
  }

  @Override
  public int getLimit() {
    return (int) state.get().currentLimit();
  }

  /**
   * Updates the algorithm state using a lock-free CAS loop.
   *
   * <p>The loop reads the current immutable state, computes a new state from it
   * (pure arithmetic, no side effects), and attempts an atomic swap. On contention
   * (another thread updated between read and CAS), it simply re-reads and recomputes
   * with the latest state — no thread ever blocks or parks.
   */
  @Override
  public void update(Duration rtt, boolean isSuccess) {
    long rttNanos = rtt.toNanos();
    if (rttNanos <= 0) return;

    VegasState current;
    VegasState next;
    do {
      current = state.get();

      // 1. Maintain the Baseline
      // Constantly track the fastest execution time — our "zero congestion" metric.
      long newNoLoad = Math.min(current.noLoadRttNanos(), rttNanos);

      // 2. Calculate Smoothed Current Latency (EWMA)
      // Filters out GC pauses and single network hiccups.
      long newSmoothed = current.smoothedRttNanos() == 0
          ? rttNanos
          : (long) (current.smoothedRttNanos() * (1 - smoothingFactor) + rttNanos * smoothingFactor);

      // 3. Compute the Gradient
      // 1.0 = perfect performance, 0.5 = system is twice as slow as baseline.
      double gradient = (double) newNoLoad / (double) newSmoothed;
      gradient = Math.max(0.5, Math.min(1.2, gradient));

      // 4. Calculate the new limit
      double newLimit;
      if (isSuccess) {
        // Proactive Adjustment: scale by gradient + additive probing factor
        newLimit = current.currentLimit() * gradient + 0.5;
      } else {
        // Reactive Adjustment: multiplicative decrease (cut by 20%)
        newLimit = current.currentLimit() * 0.8;
      }
      newLimit = Math.max(minLimit, Math.min(maxLimit, newLimit));

      next = new VegasState(newNoLoad, newSmoothed, newLimit);

    } while (!state.compareAndSet(current, next));
  }

  /**
   * Immutable snapshot of the algorithm's internal state.
   * Bundling all fields into a single record enables atomic CAS transitions.
   */
  private record VegasState(long noLoadRttNanos, long smoothedRttNanos, double currentLimit) {
  }
}
