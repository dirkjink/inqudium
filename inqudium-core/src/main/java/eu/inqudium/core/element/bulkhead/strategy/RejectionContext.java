package eu.inqudium.core.element.bulkhead.strategy;

/**
 * Immutable snapshot of the bulkhead state at the exact moment a permit was denied.
 *
 * <p>Captured <em>inside</em> the strategy's decision logic (within the CAS loop or
 * lock-guarded block), so every field reflects the true state that caused the rejection.
 * This eliminates the TOCTOU problem where a post-hoc call to
 * {@link BulkheadStrategy#concurrentCalls()} returns a value that has already changed.
 *
 * <p>Only allocated on the rejection path — the happy path returns {@code null}.
 *
 * <h2>Usage in the facade</h2>
 * <pre>{@code
 * // Blocking
 * RejectionContext rejection = strategy.tryAcquire(timeout);
 * if (rejection != null) {
 *     throw new InqBulkheadFullException(name, rejection);
 * }
 *
 * // Non-blocking
 * RejectionContext rejection = strategy.tryAcquire();
 * if (rejection != null) {
 *     return Mono.error(new InqBulkheadFullException(name, rejection));
 * }
 * }</pre>
 *
 * @param reason                the reason the permit was denied
 * @param limitAtDecision       the concurrency limit that was enforced at the moment of
 *                              rejection — for static strategies this is the configured max,
 *                              for adaptive strategies the algorithm's current output
 * @param activeCallsAtDecision the number of calls holding a permit at the moment of
 *                              rejection — captured inside the lock or CAS loop
 * @param waitedNanos           how long the calling thread actually waited before rejection;
 *                              0 for non-blocking strategies
 * @param sojournNanos          the CoDel sojourn time (time spent waiting for a permit
 *                              after entering the queue); 0 for non-CoDel strategies
 * @since 0.3.0
 */
public record RejectionContext(
    RejectionReason reason,
    int limitAtDecision,
    int activeCallsAtDecision,
    long waitedNanos,
    long sojournNanos
) {

  // ──────────────────────────────────────────────────────────────────────────
  // Factory Methods — one per rejection scenario
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * The concurrency limit was reached. No permit was available at the instant
   * the strategy checked. Used by all non-CoDel strategies for immediate rejection
   * (non-blocking) or when no permit becomes free within the timeout (blocking).
   *
   * @param limit       the limit enforced at the moment of rejection
   * @param activeCalls the number of active calls at the moment of rejection
   */
  public static RejectionContext capacityReached(int limit, int activeCalls) {
    return new RejectionContext(RejectionReason.CAPACITY_REACHED, limit, activeCalls, 0L, 0L);
  }

  /**
   * The caller's timeout expired while waiting for a permit to become available.
   * The bulkhead was at capacity for the entire wait duration.
   *
   * @param limit       the limit enforced at the moment of rejection
   * @param activeCalls the number of active calls at the moment of rejection
   * @param waitedNanos the actual time spent waiting before giving up
   */
  public static RejectionContext timeoutExpired(int limit, int activeCalls, long waitedNanos) {
    return new RejectionContext(RejectionReason.TIMEOUT_EXPIRED, limit, activeCalls, waitedNanos, 0L);
  }

  /**
   * CoDel determined that the request waited too long (sojourn time exceeded the
   * target delay for longer than one interval). A permit <em>was</em> available,
   * but the request was dropped to shed load during sustained congestion.
   *
   * @param limit        the concurrency limit at the moment of rejection
   * @param activeCalls  the number of active calls at the moment of rejection
   * @param waitedNanos  the total wall-clock time spent in tryAcquire
   * @param sojournNanos the CoDel sojourn time (post-lock queue wait)
   */
  public static RejectionContext codelDrop(int limit, int activeCalls,
                                           long waitedNanos, long sojournNanos) {
    return new RejectionContext(
        RejectionReason.CODEL_SOJOURN_EXCEEDED, limit, activeCalls, waitedNanos, sojournNanos);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Formatting
  // ──────────────────────────────────────────────────────────────────────────

  private static String formatNanos(long nanos) {
    if (nanos < 1_000_000L) {
      return nanos / 1_000L + "µs";
    }
    return nanos / 1_000_000L + "ms";
  }

  /**
   * Human-readable summary suitable for exception messages and log output.
   *
   * <p>Examples:
   * <ul>
   *   <li>{@code CAPACITY_REACHED (10/10 concurrent calls)}</li>
   *   <li>{@code TIMEOUT_EXPIRED (10/10 concurrent calls, waited 500ms)}</li>
   *   <li>{@code CODEL_SOJOURN_EXCEEDED (8/10 concurrent calls, sojourn 1200ms, waited 1500ms)}</li>
   * </ul>
   */
  @Override
  public String toString() {
    return switch (reason) {
      case CAPACITY_REACHED -> String.format(
          "%s (%d/%d concurrent calls, no wait)",
          reason, activeCallsAtDecision, limitAtDecision);

      case TIMEOUT_EXPIRED -> String.format(
          "%s (%d/%d concurrent calls, waited %s)",
          reason, activeCallsAtDecision, limitAtDecision, formatNanos(waitedNanos));

      case CODEL_SOJOURN_EXCEEDED -> String.format(
          "%s (%d/%d concurrent calls, sojourn %s, waited %s)",
          reason, activeCallsAtDecision, limitAtDecision,
          formatNanos(sojournNanos), formatNanos(waitedNanos));
    };
  }
}
