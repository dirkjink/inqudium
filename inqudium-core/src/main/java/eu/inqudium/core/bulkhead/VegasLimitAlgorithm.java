package eu.inqudium.core.bulkhead;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A latency-based adaptive limit algorithm inspired by TCP Vegas congestion control.
 *
 * <h2>Algorithm Explanation</h2>
 * <p>Unlike AIMD, which is reactive and waits for errors (like timeouts) to reduce the limit,
 * the Vegas algorithm is <b>proactive</b>. It continuously measures the Round-Trip-Time (RTT)
 * and attempts to detect queuing delay in the downstream service before failures occur.
 * * <p>It does this by calculating a "gradient":
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

  private final AtomicLong noLoadRttNanos = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong smoothedRttNanos = new AtomicLong(0);
  private final AtomicReference<Double> currentLimit;

  /**
   * Creates a new Vegas limit algorithm instance.
   *
   * @param initialLimit    The starting concurrency limit.
   * @param minLimit        The absolute minimum allowed concurrency.
   * @param maxLimit        The absolute maximum allowed concurrency.
   * @param smoothingFactor A value between 0.01 and 1.0 determining how heavily new RTT
   * measurements impact the rolling average. Lower values (e.g., 0.2)
   * make the algorithm less sensitive to sudden random spikes.
   */
  public VegasLimitAlgorithm(int initialLimit, int minLimit, int maxLimit, double smoothingFactor) {
    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.smoothingFactor = Math.max(0.01, Math.min(1.0, smoothingFactor));
    this.currentLimit = new AtomicReference<>((double) Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit)));
  }

  @Override
  public int getLimit() {
    return currentLimit.get().intValue();
  }

  @Override
  public void update(Duration rtt, boolean isSuccess) {
    long rttNanos = rtt.toNanos();
    if (rttNanos <= 0) return;

    // 1. Maintain the Baseline
    // Constantly track the fastest execution time. This is our "zero congestion" metric.
    noLoadRttNanos.updateAndGet(currentMin -> Math.min(currentMin, rttNanos));

    // 2. Calculate Smoothed Current Latency
    // We use EWMA (Exponential Weighted Moving Average) to avoid overreacting to garbage
    // collection pauses or single network hiccups.
    long currentSmoothed = smoothedRttNanos.updateAndGet(current ->
        current == 0 ? rttNanos : (long) (current * (1 - smoothingFactor) + rttNanos * smoothingFactor)
    );

    // 3. Compute the Gradient
    // A gradient of 1.0 means perfect performance. A gradient of 0.5 means the system
    // is twice as slow as the baseline.
    double noLoad = noLoadRttNanos.get();
    double gradient = noLoad / (double) currentSmoothed;

    // We constrain the gradient to prevent extreme, violent adjustments in a single update step.
    gradient = Math.max(0.5, Math.min(1.2, gradient));

    if (isSuccess) {
      // 4a. Proactive Adjustment (Success)
      // We scale the limit by the gradient. If the system is slow (gradient < 1), the limit
      // shrinks. We add 0.5 as an additive probing factor to slowly grow the limit if the
      // gradient is exactly 1.0.
      double finalGradient = gradient;
      currentLimit.updateAndGet(limit -> {
        double newLimit = limit * finalGradient + 0.5;
        return Math.max(minLimit, Math.min(maxLimit, newLimit));
      });
    } else {
      // 4b. Reactive Adjustment (Failure)
      // If an actual error or timeout occurs, Vegas acts like AIMD and performs a
      // multiplicative decrease (cutting capacity by 20%) to aggressively protect the backend.
      currentLimit.updateAndGet(limit -> Math.max(minLimit, limit * 0.8));
    }
  }
}