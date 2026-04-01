package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;

import java.time.Instant;
import java.util.Arrays;

/**
 * Immutable implementation of a count-based sliding window algorithm.
 * It tracks the outcomes of the last N calls using a circular buffer.
 */
public record SlidingWindowMetrics(
    int windowSize,
    int minimumNumberOfCalls,
    boolean[] window,
    int headIndex,
    int size,
    int failureCount
) implements FailureMetrics {

  /**
   * Creates a new, empty sliding window metrics instance.
   *
   * @param windowSize           the maximum number of calls to track in the window
   * @param minimumNumberOfCalls the minimum number of recorded calls before evaluating the threshold
   */
  public static SlidingWindowMetrics initial(int windowSize, int minimumNumberOfCalls) {
    if (windowSize <= 0) {
      throw new IllegalArgumentException("windowSize must be greater than 0");
    }
    if (minimumNumberOfCalls <= 0 || minimumNumberOfCalls > windowSize) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be between 1 and windowSize");
    }
    return new SlidingWindowMetrics(windowSize, minimumNumberOfCalls, new boolean[windowSize], -1, 0, 0);
  }

  @Override
  public FailureMetrics recordSuccess(Instant now) {
    return recordOutcome(false);
  }

  @Override
  public FailureMetrics recordFailure(Instant now) {
    return recordOutcome(true);
  }

  /**
   * Internal method to record an outcome immutably.
   *
   * @param isFailure true if the outcome was a failure, false for success
   * @return a new SlidingWindowMetrics instance with the updated state
   */
  private SlidingWindowMetrics recordOutcome(boolean isFailure) {
    // Create a defensive copy of the window array to ensure immutability
    boolean[] newWindow = Arrays.copyOf(window, windowSize);

    // Calculate the new head index for the circular buffer
    int newHeadIndex = (headIndex + 1) % windowSize;

    // Check if the window is full and we are about to overwrite an old outcome.
    // We only need to reduce the failure count if the overwritten outcome was a failure.
    boolean oldOutcome = (size == windowSize) && newWindow[newHeadIndex];

    // Store the new outcome
    newWindow[newHeadIndex] = isFailure;

    // Calculate new metrics
    int newSize = Math.min(windowSize, size + 1);
    int newFailureCount = failureCount + (isFailure ? 1 : 0) - (oldOutcome ? 1 : 0);

    return new SlidingWindowMetrics(
        windowSize,
        minimumNumberOfCalls,
        newWindow,
        newHeadIndex,
        newSize,
        newFailureCount
    );
  }

  @Override
  public boolean isThresholdReached(CircuitBreakerConfig config, Instant now) {
    // Do not trip the circuit if we haven't gathered enough data points yet
    if (size < minimumNumberOfCalls) {
      return false;
    }

    // Evaluate against the absolute failure threshold from the configuration.
    // For example, if config says threshold is 5, and we have 5 failures in
    // our window size of 10, this will return true.
    return failureCount >= config.failureThreshold();
  }

  @Override
  public FailureMetrics reset(Instant now) {
    // Return a fresh, empty window with the same configuration
    return initial(windowSize, minimumNumberOfCalls);
  }

  @Override
  public String getTripReason(CircuitBreakerConfig config, Instant now) {
    return "Sliding window threshold reached: Found %d failures in the last %d calls (Threshold: %d)."
        .formatted(failureCount, size, config.failureThreshold());
  }
}
