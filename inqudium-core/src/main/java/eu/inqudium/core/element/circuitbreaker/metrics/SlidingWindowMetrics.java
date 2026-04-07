package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.Arrays;

/**
 * Immutable implementation of a count-based sliding window algorithm.
 * Tracks the outcomes of the last N calls using a circular buffer.
 */
public record SlidingWindowMetrics(
    int maxFailuresInWindow,
    int windowSize,
    int minimumNumberOfCalls,
    boolean[] window,
    int headIndex,
    int size,
    int failureCount
) implements FailureMetrics {

  public static SlidingWindowMetrics initial(int maxFailuresInWindow, int windowSize, int minimumNumberOfCalls) {
    if (windowSize <= 0) {
      throw new IllegalArgumentException("windowSize must be greater than 0");
    }
    if (minimumNumberOfCalls <= 0 || minimumNumberOfCalls > windowSize) {
      throw new IllegalArgumentException("minimumNumberOfCalls must be between 1 and windowSize");
    }
    return new SlidingWindowMetrics(
        maxFailuresInWindow,
        windowSize,
        minimumNumberOfCalls,
        new boolean[windowSize],
        -1,
        0,
        0);
  }

  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return recordOutcome(false);
  }

  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return recordOutcome(true);
  }

  private SlidingWindowMetrics recordOutcome(boolean isFailure) {
    boolean[] newWindow = Arrays.copyOf(window, windowSize);
    int newHeadIndex = (headIndex + 1) % windowSize;
    boolean oldOutcome = (size == windowSize) && newWindow[newHeadIndex];
    newWindow[newHeadIndex] = isFailure;
    int newSize = Math.min(windowSize, size + 1);
    int newFailureCount = failureCount + (isFailure ? 1 : 0) - (oldOutcome ? 1 : 0);

    return new SlidingWindowMetrics(
        maxFailuresInWindow, windowSize, minimumNumberOfCalls,
        newWindow, newHeadIndex, newSize, newFailureCount);
  }

  @Override
  public boolean isThresholdReached(long nowNanos) {
    if (size < minimumNumberOfCalls) {
      return false;
    }
    return failureCount >= maxFailuresInWindow;
  }

  @Override
  public FailureMetrics reset(long nowNanos) {
    return new SlidingWindowMetrics(
        maxFailuresInWindow, windowSize, minimumNumberOfCalls,
        new boolean[windowSize], -1, 0, 0);
  }

  @Override
  public String getTripReason(long nowNanos) {
    return "Sliding window threshold reached: Found %d failures in the last %d calls (Threshold: %d)."
        .formatted(failureCount, size, maxFailuresInWindow);
  }
}
