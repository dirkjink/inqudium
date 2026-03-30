package eu.inqudium.core.bulkhead;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe implementation of the Additive Increase, Multiplicative Decrease (AIMD) algorithm.
 * * @since 0.2.0
 */
public final class AimdLimitAlgorithm implements InqLimitAlgorithm {

  private final int minLimit;
  private final int maxLimit;
  private final double backoffRatio;

  private final AtomicInteger currentLimit;

  public AimdLimitAlgorithm(int initialLimit, int minLimit, int maxLimit, double backoffRatio) {
    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.backoffRatio = Math.max(0.1, Math.min(0.9, backoffRatio));
    this.currentLimit = new AtomicInteger(Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit)));
  }

  @Override
  public int getLimit() {
    return currentLimit.get();
  }

  @Override
  public void update(Duration rtt, boolean isSuccess) {
    if (isSuccess) {
      // Additive Increase: Slowly increase the limit to probe for more capacity
      currentLimit.updateAndGet(current -> Math.min(maxLimit, current + 1));
    } else {
      // Multiplicative Decrease: Rapidly drop the limit to shed load and protect the system
      currentLimit.updateAndGet(current -> {
        int newLimit = (int) (current * backoffRatio);
        return Math.max(minLimit, newLimit);
      });
    }
  }
}
