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
 * It only computes the next state and the required delay. The wrappers
 * decide how to honour the delay:
 * <ul>
 *   <li>Imperative: {@code LockSupport.parkNanos} / {@code Thread.sleep}</li>
 *   <li>Reactive: {@code Mono.delay}</li>
 * </ul>
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
   * <p>This is the central decision function. It checks:
   * <ol>
   *   <li>Is the exception retryable according to the config's predicate?</li>
   *   <li>Are there remaining attempts?</li>
   *   <li>If yes to both: compute the backoff delay and return {@link RetryDecision.DoRetry}.</li>
   * </ol>
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

    // Check if the exception is retryable
    if (!config.shouldRetryOnException(failure)) {
      return new RetryDecision.DoNotRetry(
          snapshot.withFailed(failure), failure);
    }

    // Check if retries remain
    if (snapshot.attemptNumber() >= config.maxAttempts()) {
      return new RetryDecision.RetriesExhausted(
          snapshot.withExhausted(failure), failure);
    }

    // Compute backoff delay — retryIndex is 0-based
    int retryIndex = snapshot.attemptNumber() - 1;
    Duration delay = config.backoffStrategy().computeDelay(retryIndex);

    return new RetryDecision.DoRetry(
        snapshot.withRetryScheduled(failure, delay), delay, retryIndex);
  }

  /**
   * Evaluates a result and decides whether it should trigger a retry.
   *
   * <p>Used when the config has a result predicate (e.g. retry on null).
   *
   * @param snapshot the current snapshot (must be in ATTEMPTING)
   * @param config   the retry configuration
   * @param result   the result to evaluate
   * @return a retry decision, or {@code null} if the result is acceptable
   */
  public static RetryDecision evaluateResult(
      RetrySnapshot snapshot,
      RetryConfig config,
      Object result) {

    requireState(snapshot, RetryState.ATTEMPTING, "evaluateResult");

    if (!config.shouldRetryOnResult(result)) {
      return null; // Result is acceptable — no retry needed
    }

    if (snapshot.attemptNumber() >= config.maxAttempts()) {
      // Fix 2B: Synthetischen Fehler erzeugen, anstatt hier schon eine RetryException zu bauen
      Throwable syntheticFailure = new RuntimeException("Unacceptable result: " + result);
      return new RetryDecision.RetriesExhausted(
          snapshot.withExhausted(syntheticFailure),
          snapshot.lastFailure() != null ? snapshot.lastFailure() : syntheticFailure);
    }

    int retryIndex = snapshot.attemptNumber() - 1;
    Duration delay = config.backoffStrategy().computeDelay(retryIndex);

    return new RetryDecision.DoRetry(
        snapshot.withResultRetryScheduled(delay), delay, retryIndex);
  }

  // ======================== Query Helpers ========================

  /**
   * Returns the total number of attempts performed so far.
   */
  public static int attemptCount(RetrySnapshot snapshot) {
    return snapshot.totalAttempts();
  }

  /**
   * Returns the number of retries performed (attempts - 1).
   */
  public static int retryCount(RetrySnapshot snapshot) {
    return snapshot.retryCount();
  }

  /**
   * Returns whether retries remain for the given snapshot and config.
   */
  public static boolean hasRetriesRemaining(RetrySnapshot snapshot, RetryConfig config) {
    return snapshot.attemptNumber() < config.maxAttempts();
  }

  /**
   * Returns the total elapsed duration since the first attempt.
   */
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
}
