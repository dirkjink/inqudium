package eu.inqudium.core.bulkhead;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A latency-based adaptive limit algorithm inspired by TCP Vegas.
 *
 * <p>It compares the current average Round-Trip-Time (RTT) against the fastest
 * historically measured RTT (no-load RTT). If the current RTT significantly
 * exceeds the no-load RTT, it assumes queuing is happening downstream and
 * smoothly reduces the limit. If latency is good, it probes for more capacity.
 *
 * @since 0.2.0
 */
public final class VegasLimitAlgorithm implements InqLimitAlgorithm {

  private final int minLimit;
  private final int maxLimit;
  private final double smoothingFactor; // e.g., 0.2 for exponential smoothing

  private final AtomicLong noLoadRttNanos = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong smoothedRttNanos = new AtomicLong(0);
  private final AtomicReference<Double> currentLimit;

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

    // 1. Update the theoretical best-case latency (No-Load RTT)
    noLoadRttNanos.updateAndGet(currentMin -> Math.min(currentMin, rttNanos));

    // 2. Exponential Weighted Moving Average (EWMA) for current latency
    long currentSmoothed = smoothedRttNanos.updateAndGet(current ->
        current == 0 ? rttNanos : (long) (current * (1 - smoothingFactor) + rttNanos * smoothingFactor)
    );

    // 3. Calculate the gradient
    // If current latency is 2x the no-load latency, gradient is 0.5 (need to reduce)
    // If current latency is equal to no-load latency, gradient is 1.0 (can maintain/probe)
    double noLoad = noLoadRttNanos.get();
    double gradient = noLoad / (double) currentSmoothed;

    // Cap the gradient to prevent extreme jumps (e.g., max halving or max 20% growth)
    gradient = Math.max(0.5, Math.min(1.2, gradient));

    if (isSuccess) {
      // Apply gradient and add a small probing factor
      double finalGradient = gradient;
      currentLimit.updateAndGet(limit -> {
        double newLimit = limit * finalGradient + 0.5; // +0.5 is the additive probe
        return Math.max(minLimit, Math.min(maxLimit, newLimit));
      });
    } else {
      // On absolute failure (e.g., 503 Service Unavailable), we still penalize heavily
      // to protect the backend, acting similarly to AIMD's multiplicative decrease.
      currentLimit.updateAndGet(limit -> Math.max(minLimit, limit * 0.8));
    }
  }
}
