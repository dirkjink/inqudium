package eu.inqudium.core.element.bulkhead.algo;

import java.time.Duration;

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
   * @param rtt       the round-trip time of the call
   * @param isSuccess true if no business or technical error occurred
   */
  void update(Duration rtt, boolean isSuccess);
}
