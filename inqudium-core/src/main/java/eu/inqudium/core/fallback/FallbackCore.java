package eu.inqudium.core.fallback;

/**
 * Pure functional core of the fallback provider state machine.
 *
 * <p>All methods are static and side-effect-free. They accept the current
 * immutable {@link FallbackSnapshot} and return a new snapshot reflecting
 * the state transition.
 *
 * <p><strong>API contract:</strong> Methods must be called in the order dictated
 * by the {@link FallbackState} state machine. Calling a method in an unexpected
 * state will throw {@link IllegalStateException}. The valid call sequences are:
 *
 * <pre>
 *   start → recordPrimarySuccess                          (happy path)
 *   start → [withFallingBack] → recordFallbackSuccess     (recovered)
 *   start → [withFallingBack] → recordFallbackFailure     (fallback failed)
 * </pre>
 *
 * <p><strong>Note on snapshot observability (Fix 5):</strong> The snapshot
 * currently serves as a structured timing container and state-machine guard.
 * It is not yet exposed to callers. Future extensions (e.g., metrics aggregation,
 * execution result wrappers) may expose the final snapshot. The state machine
 * validation ensures correctness even if the snapshot is later made observable.
 */
public final class FallbackCore {

  private FallbackCore() {
    // Utility class — not instantiable
  }

  // ======================== Lifecycle transitions ========================

  /**
   * Starts a new execution, transitioning from IDLE to EXECUTING.
   *
   * @param currentNanos the current {@code System.nanoTime()} value
   * @return a new snapshot in EXECUTING state
   */
  public static FallbackSnapshot start(long currentNanos) {
    return FallbackSnapshot.idle().withExecuting(currentNanos);
  }

  /**
   * Records that the primary operation succeeded.
   * Requires the snapshot to be in EXECUTING state.
   *
   * @param snapshot     the current snapshot (must be EXECUTING)
   * @param currentNanos the current {@code System.nanoTime()} value
   * @return a new snapshot in SUCCEEDED state
   * @throws IllegalStateException if the snapshot is not in EXECUTING state
   */
  public static FallbackSnapshot recordPrimarySuccess(FallbackSnapshot snapshot, long currentNanos) {
    requireState(snapshot, FallbackState.EXECUTING, "recordPrimarySuccess");
    return snapshot.withSucceeded(currentNanos);
  }

  /**
   * Records that the fallback handler succeeded.
   * Requires the snapshot to be in FALLING_BACK state.
   *
   * @param snapshot     the current snapshot (must be FALLING_BACK)
   * @param currentNanos the current {@code System.nanoTime()} value
   * @return a new snapshot in RECOVERED state
   * @throws IllegalStateException if the snapshot is not in FALLING_BACK state
   */
  public static FallbackSnapshot recordFallbackSuccess(FallbackSnapshot snapshot, long currentNanos) {
    requireState(snapshot, FallbackState.FALLING_BACK, "recordFallbackSuccess");
    return snapshot.withRecovered(currentNanos);
  }

  /**
   * Records that the fallback handler itself failed.
   * Requires the snapshot to be in FALLING_BACK state.
   *
   * @param snapshot        the current snapshot (must be FALLING_BACK)
   * @param fallbackFailure the exception thrown by the fallback handler
   * @param currentNanos    the current {@code System.nanoTime()} value
   * @return a new snapshot in FALLBACK_FAILED state
   * @throws IllegalStateException if the snapshot is not in FALLING_BACK state
   */
  public static FallbackSnapshot recordFallbackFailure(
      FallbackSnapshot snapshot,
      Throwable fallbackFailure,
      long currentNanos) {

    requireState(snapshot, FallbackState.FALLING_BACK, "recordFallbackFailure");
    return snapshot.withFallbackFailed(fallbackFailure, currentNanos);
  }

  // ======================== Handler invocation ========================

  @SuppressWarnings("unchecked")
  public static <T> T invokeExceptionHandler(FallbackExceptionHandler<T> handler, Throwable failure) {
    return switch (handler) {
      case FallbackExceptionHandler.ForExceptionType<T, ?> typed -> typed.apply(failure);
      case FallbackExceptionHandler.ForExceptionPredicate<T> predicated -> predicated.apply(failure);
      case FallbackExceptionHandler.CatchAll<T> catchAll -> catchAll.apply(failure);
      case FallbackExceptionHandler.ConstantValue<T> constant -> constant.apply();
    };
  }

  public static <T> T invokeResultHandler(FallbackResultHandler<T> handler, T result) {
    return handler.apply(result);
  }

  // ======================== Internal ========================

  private static void requireState(FallbackSnapshot snapshot, FallbackState required, String operation) {
    if (snapshot.state() != required) {
      throw new IllegalStateException(
          "Cannot %s in state %s (expected %s)".formatted(operation, snapshot.state(), required));
    }
  }
}
