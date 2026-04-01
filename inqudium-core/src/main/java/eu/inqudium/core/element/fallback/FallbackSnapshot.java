package eu.inqudium.core.element.fallback;

import java.time.Duration;

/**
 * Immutable snapshot of a single fallback-protected execution's state.
 */
public record FallbackSnapshot(
    FallbackState state,
    Throwable primaryFailure,
    Throwable fallbackFailure,
    String handlerName,
    long startNanos,
    long fallbackStartNanos,
    long endNanos
) {

  public static FallbackSnapshot idle() {
    return new FallbackSnapshot(
        FallbackState.IDLE, null, null, null, 0L, 0L, 0L);
  }

  public FallbackSnapshot withExecuting(long currentNanos) {
    return new FallbackSnapshot(
        FallbackState.EXECUTING, null, null, null, currentNanos, 0L, 0L);
  }

  public FallbackSnapshot withSucceeded(long currentNanos) {
    return new FallbackSnapshot(
        FallbackState.SUCCEEDED, null, null, null, startNanos, 0L, currentNanos);
  }

  public FallbackSnapshot withFallingBack(Throwable primaryFailure, String handlerName, long currentNanos) {
    return new FallbackSnapshot(
        FallbackState.FALLING_BACK, primaryFailure, null, handlerName, startNanos, currentNanos, 0L);
  }

  public FallbackSnapshot withRecovered(long currentNanos) {
    return new FallbackSnapshot(
        FallbackState.RECOVERED, primaryFailure, null, handlerName, startNanos, fallbackStartNanos, currentNanos);
  }

  public FallbackSnapshot withFallbackFailed(Throwable fallbackFailure, long currentNanos) {
    return new FallbackSnapshot(
        FallbackState.FALLBACK_FAILED, primaryFailure, fallbackFailure,
        handlerName, startNanos, fallbackStartNanos, currentNanos);
  }

  public FallbackSnapshot withUnhandled(Throwable primaryFailure, long currentNanos) {
    return new FallbackSnapshot(
        FallbackState.UNHANDLED, primaryFailure, null, null, startNanos, 0L, currentNanos);
  }

  public Duration elapsed(long currentNanos) {
    if (startNanos == 0L) {
      return Duration.ZERO;
    }
    long end = endNanos != 0L ? endNanos : currentNanos;
    return Duration.ofNanos(end - startNanos);
  }

  public Duration fallbackElapsed(long currentNanos) {
    if (fallbackStartNanos == 0L) {
      return Duration.ZERO;
    }
    long end = endNanos != 0L ? endNanos : currentNanos;
    return Duration.ofNanos(end - fallbackStartNanos);
  }

  public boolean fallbackInvoked() {
    return handlerName != null;
  }
}