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

  /**
   * Attempts to acquire a permit immediately without blocking.
   * This is used by reactive/coroutine paradigms that cannot block the thread.
   * * @param callId the unique call identifier for tracing/events
   *
   * @return {@code true} if acquired, {@code false} if full
   */
  boolean tryAcquireNonBlocking(String callId);

  int getMaxConcurrentCalls();

  /**
   * Attempts to acquire a permit, potentially blocking the thread up to the timeout.
   * This is strictly for imperative paradigms.
   * * @param callId the unique call identifier
   *
   * @param timeout the maximum duration to wait
   * @return {@code true} if acquired, {@code false} if full
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  boolean tryAcquireBlocking(String callId, Duration timeout) throws InterruptedException;

  /**
   * Releases a previously acquired permit and reports the execution metrics.
   * * @param callId the unique call identifier
   *
   * @param rtt   the exact Round-Trip-Time measured by the paradigm
   * @param error the business exception thrown by the call (or null if successful)
   */
  void releaseAndReport(String callId, Duration rtt, Throwable error);

  int getAvailablePermits();

  int getConcurrentCalls();
}
