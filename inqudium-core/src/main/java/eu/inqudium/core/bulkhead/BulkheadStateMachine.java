package eu.inqudium.core.bulkhead;

import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

/**
 * The paradigm-agnostic state machine of a bulkhead.
 * * <p>It handles permit counting, telemetry (events), and adaptive limit calculations.
 * It strictly DOES NOT handle execution, thread-blocking, or exception catching.
 * * @since 0.2.0
 */
public interface BulkheadStateMachine {

  InqEventPublisher getEventPublisher();

  int getMaxConcurrentCalls();

  /**
   * Releases a previously acquired permit and reports the execution metrics.
   * * @param callId the unique call identifier
   *
   * <h4>Late RTT Measurement in Synchronous Code</h4>
   * The startNanos is measured after tryAcquire has blocked.
   * This is perfectly correct for Vegas and AIMD, as they require pure downstream metrics
   * (business logic). Keep this in mind: The RTT reported to releaseAndReport does not reflect
   * the total call time from the caller's perspective, but only the execution time after
   * the bulkhead is released.
   *
   * @param rtt   the exact Round-Trip-Time measured by the paradigm
   * @param error the business exception thrown by the call (or null if successful)
   */
  void releaseAndReport(String callId, Duration rtt, Throwable error);

  int getAvailablePermits();

  int getConcurrentCalls();
}
