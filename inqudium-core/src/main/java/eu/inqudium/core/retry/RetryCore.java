package eu.inqudium.core.retry;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure functional core of the retry state machine.
 *
 * <p>All methods are static and side-effect-free. They manage the
 * per-execution retry lifecycle as an immutable state machine:
 *
 * <pre>
 *   IDLE ──[startAttempt]──► ATTEMPTING ──[recordSuccess]──► COMPLETED
 *                                │
 *                                ├──[evaluateFailure → retryable]──► WAITING_FOR_RETRY ──► ATTEMPTING
 *                                │
 *                                ├──[evaluateFailure → not retryable]──► FAILED
 *                                │
 *                                └──[evaluateFailure → exhausted]──► EXHAUSTED
 * </pre>
 *
 * <p>Like TimeLimiter, there is no shared mutable state between executions.
 * Each execution gets its own {@link RetrySnapshot}.
 *
 * <p>The core does <strong>not</strong> perform any waiting or scheduling.
 * It only computes the next state and the required delay.
 */
public final class RetryCore {

  private RetryCore() {
    // Utility class — not instantiable
  }

  // ======================== Lifecycle Transitions ========================

  /**
   * Starts the first attempt. Transitions IDLE → ATTEMPTING.
   *
   * @param now the current time
   * @return a snapshot in ATTEMPTING state for attempt #1
   */
  public static RetrySnapshot startFirstAttempt(Instant now) {
    return RetrySnapshot.idle().withAttemptStarted(1, now);
  }

  /**
   * Starts the next retry attempt. Transitions WAITING_FOR_RETRY → ATTEMPTING.
   *
   * @param snapshot the current snapshot (must be in WAITING_FOR_RETRY)
   * @param now      the current time
   * @return a snapshot in ATTEMPTING state for the next attempt
   * @throws IllegalStateException if not in WAITING_FOR_RETRY state
   */
  public static RetrySnapshot startNextAttempt(RetrySnapshot snapshot, Instant now) {
    requireState(snapshot, RetryState.WAITING_FOR_RETRY, "startNextAttempt");
    return snapshot.withAttemptStarted(snapshot.attemptNumber() + 1, now);
  }

  /**
   * Records a successful attempt. Transitions ATTEMPTING → COMPLETED.
   *
   * @param snapshot the current snapshot (must be in ATTEMPTING)
   * @return a snapshot in COMPLETED state
   * @throws IllegalStateException if not in ATTEMPTING state
   */
  public static RetrySnapshot recordSuccess(RetrySnapshot snapshot) {
    requireState(snapshot, RetryState.ATTEMPTING, "recordSuccess");
    return snapshot.withCompleted();
  }

  // ======================== Failure Evaluation ========================

  /**
   * Evaluates a failure and decides whether to retry, fail, or declare exhaustion.
   *
   * @param snapshot the current snapshot (must be in ATTEMPTING)
   * @param config   the retry configuration
   * @param failure  the exception from the failed attempt
   * @return the retry decision
   * @throws IllegalStateException if not in ATTEMPTING state
   */
  public static RetryDecision evaluateFailure(
      RetrySnapshot snapshot,
      RetryConfig config,
      Throwable failure) {

    requireState(snapshot, RetryState.ATTEMPTING, "evaluateFailure");

    if (!config.shouldRetryOnException(failure)) {
      return new RetryDecision.DoNotRetry(
          snapshot.withFailed(failure), failure);
    }

    if (snapshot.attemptNumber() >= config.maxAttempts()) {
      return new RetryDecision.RetriesExhausted(
          snapshot.withExhausted(failure), failure, false);
    }

    int retryIndex = snapshot.attemptNumber() - 1;
    Duration previousDelay = previousDelayOrZero(snapshot);
    Duration delay = config.backoffStrategy().computeDelay(retryIndex, previousDelay);

    return new RetryDecision.DoRetry(
        snapshot.withRetryScheduled(failure, delay), delay, retryIndex);
  }

  /**
   * Evaluates a result and decides whether it should trigger a retry.
   *
   * @param snapshot the current snapshot (must be in ATTEMPTING)
   * @param config   the retry configuration
   * @param result   the result to evaluate
   * @return a retry decision — never null
   */
  public static RetryDecision evaluateResult(
      RetrySnapshot snapshot,
      RetryConfig config,
      Object result) {

    requireState(snapshot, RetryState.ATTEMPTING, "evaluateResult");

    if (!config.shouldRetryOnResult(result)) {
      return new RetryDecision.Accept(snapshot.withCompleted());
    }

    if (snapshot.attemptNumber() >= config.maxAttempts()) {
      Throwable syntheticFailure = new RetryOnResultExhaustedException(result);
      return new RetryDecision.RetriesExhausted(
          snapshot.withExhausted(syntheticFailure),
          syntheticFailure,
          true);
    }

    int retryIndex = snapshot.attemptNumber() - 1;
    Duration previousDelay = previousDelayOrZero(snapshot);
    Duration delay = config.backoffStrategy().computeDelay(retryIndex, previousDelay);

    return new RetryDecision.DoRetry(
        snapshot.withResultRetryScheduled(delay), delay, retryIndex);
  }

  // ======================== Query Helpers ========================

  public static int attemptCount(RetrySnapshot snapshot) {
    return snapshot.totalAttempts();
  }

  public static int retryCount(RetrySnapshot snapshot) {
    return snapshot.retryCount();
  }

  public static boolean hasRetriesRemaining(RetrySnapshot snapshot, RetryConfig config) {
    return snapshot.attemptNumber() < config.maxAttempts();
  }

  public static Duration totalElapsed(RetrySnapshot snapshot, Instant now) {
    return snapshot.totalElapsed(now);
  }

  // ======================== Internal ========================

  private static void requireState(RetrySnapshot snapshot, RetryState required, String operation) {
    if (snapshot.state() != required) {
      throw new IllegalStateException(
          "Cannot %s in state %s (expected %s)".formatted(operation, snapshot.state(), required));
    }
  }

  /**
   * Extracts the previous retry delay from the snapshot, or {@link Duration#ZERO}
   * if this is the first retry. Used to feed stateful backoff strategies
   * (e.g., {@link BackoffStrategy.DecorrelatedJitter}).
   */
  private static Duration previousDelayOrZero(RetrySnapshot snapshot) {
    return snapshot.nextRetryDelay() != null ? snapshot.nextRetryDelay() : Duration.ZERO;
  }

  /**
   * Dedicated exception for result-based retry exhaustion.
   * Clearly distinguishes "unacceptable result after all retries" from
   * "exception-based failure after all retries".
   *
   * <p><strong>Fix 7:</strong> The result's {@code toString()} is NOT included
   * in the exception message to avoid leaking sensitive data (e.g., API responses
   * with credentials) into logs. The result object is available programmatically
   * via {@link #getResult()} for callers that need to inspect it.
   */
  public static class RetryOnResultExhaustedException extends RuntimeException {
    private final Object result;

    public RetryOnResultExhaustedException(Object result) {
      super("Retry exhausted: unacceptable result after all attempts (type: %s)"
          .formatted(result == null ? "null" : result.getClass().getName()));
      this.result = result;
    }

    /**
     * Returns the unacceptable result that caused the exhaustion.
     */
    public Object getResult() {
      return result;
    }
  }
}
