package eu.inqudium.core.element.bulkhead.algo;

/**
 * Strategy for dynamically calculating concurrency limits based on telemetry.
 */
public interface InqLimitAlgorithm {

  /**
   * Returns the currently optimal maximum number of concurrent calls.
   */
  int getLimit();

  /**
   * Updates the internal mathematical model based on the result of a completed call.
   *
   * @param rttNanos  the round-trip time of the call in nanoseconds
   * @param isSuccess true if no business or technical error occurred
   * @param inFlightCalls the number of calls currently in flight
   */
  void update(long rttNanos, boolean isSuccess, int inFlightCalls);
}
