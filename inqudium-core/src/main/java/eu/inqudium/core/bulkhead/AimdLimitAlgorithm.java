package eu.inqudium.core.bulkhead;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe implementation of the Additive Increase, Multiplicative Decrease (AIMD) algorithm.
 *
 * <p><b>Algorithm Explanation:</b>
 * The AIMD algorithm acts as a dynamic feedback loop for concurrency limits, originally
 * made famous by its use in TCP congestion control. It continuously adjusts the allowed
 * concurrency based on the success or failure of ongoing requests:
 * <ul>
 * <li><b>Additive Increase (Probing):</b> For every successful call, the limit is increased
 * by a small, constant amount (in this implementation: +1). This allows the bulkhead
 * to gently and linearly probe the downstream system to see if it can handle more load.</li>
 * <li><b>Multiplicative Decrease (Protecting):</b> The moment a call fails (e.g., due to a
 * timeout, resource exhaustion, or a 503 error), the limit is immediately multiplied
 * by a fractional factor (e.g., 0.5). This drastically cuts the traffic instantly,
 * providing immediate relief to the struggling downstream service.</li>
 * </ul>
 * This behavior creates a characteristic "sawtooth" graph over time, as the limit slowly
 * climbs up until a failure occurs, drops sharply, and then begins to climb again.
 *
 * <p><b>Advantages:</b>
 * <ul>
 * <li><b>Rapid System Relief:</b> It reacts aggressively to failures. By halving the limit
 * instantly, it prevents cascading system crashes and gives the backend breathing room.</li>
 * <li><b>Proven Stability:</b> It guarantees mathematical convergence to an equitable share
 * of resources, which is why it has been the backbone of internet traffic for decades.</li>
 * <li><b>Computational Simplicity:</b> It requires very little memory (just an integer) and
 * almost zero CPU overhead, making it ideal for the critical hot-path of a request.</li>
 * </ul>
 *
 * <p><b>Disadvantages:</b>
 * <ul>
 * <li><b>Sawtooth Inefficiency:</b> The limit never perfectly settles on the "optimal" value;
 * it is always either drifting slightly above it (causing a minor error) or dropping
 * below it (underutilizing capacity).</li>
 * <li><b>Overreaction to Transients:</b> A single, random network hiccup will instantly slash
 * the capacity, even if the backend is actually perfectly healthy.</li>
 * <li><b>Slow Recovery:</b> Because recovery is strictly additive (+1), bouncing back from a
 * massive multiplicative drop takes time, temporarily penalizing throughput.</li>
 * </ul>
 *
 * @since 0.2.0
 */
public final class AimdLimitAlgorithm implements InqLimitAlgorithm {

  private final int minLimit;
  private final int maxLimit;
  private final double backoffRatio;

  private final AtomicInteger currentLimit;

  /**
   * Creates a new AIMD algorithm instance.
   *
   * @param initialLimit The starting concurrency limit before any feedback is received.
   * @param minLimit     The absolute minimum limit (must be >= 1) to ensure the system never
   * completely locks up and can always send at least some probe requests.
   * @param maxLimit     The absolute upper bound to prevent infinite scaling.
   * @param backoffRatio The multiplier used during the decrease phase (e.g., 0.5 for halving,
   * 0.8 for a milder 20% reduction). Must be strictly between 0.1 and 0.9.
   */
  public AimdLimitAlgorithm(int initialLimit, int minLimit, int maxLimit, double backoffRatio) {
    this.minLimit = Math.max(1, minLimit);
    this.maxLimit = Math.max(this.minLimit, maxLimit);
    this.backoffRatio = Math.max(0.1, Math.min(0.9, backoffRatio));

    // Ensure the initial limit is safely bounded within min and max
    this.currentLimit = new AtomicInteger(Math.max(this.minLimit, Math.min(initialLimit, this.maxLimit)));
  }

  @Override
  public int getLimit() {
    return currentLimit.get();
  }

  @Override
  public void update(Duration rtt, boolean isSuccess) {
    if (isSuccess) {
      // Additive Increase phase
      // The call was successful. We increment the limit by 1 to gently test if the
      // downstream service has more capacity available, capping it at maxLimit.
      currentLimit.updateAndGet(current -> Math.min(maxLimit, current + 1));
    } else {
      // Multiplicative Decrease phase
      // The call failed (likely due to overload). We immediately multiply the limit
      // by the backoff ratio to aggressively shed load, ensuring it never drops below minLimit.
      currentLimit.updateAndGet(current -> {
        int newLimit = (int) (current * backoffRatio);
        return Math.max(minLimit, newLimit);
      });
    }
  }
}