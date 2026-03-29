package eu.inqudium.core.circuitbreaker;

/**
 * Snapshot of the sliding window state after recording an outcome.
 *
 * @param failureRate     failure percentage (0.0 to 100.0)
 * @param slowCallRate    slow call percentage (0.0 to 100.0)
 * @param totalCalls      total calls in the window
 * @param failedCalls     failed calls in the window
 * @param slowCalls       slow calls in the window (independent of success/failure)
 * @param successfulCalls successful calls in the window
 * @param windowSize      configured window size (count or seconds)
 * @since 0.1.0
 */
public record WindowSnapshot(
    float failureRate,
    float slowCallRate,
    int totalCalls,
    int failedCalls,
    int slowCalls,
    int successfulCalls,
    int windowSize
) {
  /**
   * Returns whether the window has accumulated enough calls for a meaningful rate.
   *
   * @param minimumNumberOfCalls the configured minimum
   * @return true if totalCalls >= minimumNumberOfCalls
   */
  public boolean hasMinimumCalls(int minimumNumberOfCalls) {
    return totalCalls >= minimumNumberOfCalls;
  }
}
