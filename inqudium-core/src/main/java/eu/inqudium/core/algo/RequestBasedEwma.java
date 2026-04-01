package eu.inqudium.core.algo;

/**
 * A stateless calculator for a Request-Based (Discrete) Exponentially Weighted Moving Average (EWMA).
 *
 * <p>Unlike a {@link ContinuousTimeEwma}, which decays based on the exact elapsed time between
 * events, this implementation decays per discrete event (e.g., per request). This means the
 * average will artificially accelerate under high throughput: 1000 requests in one second will
 * shift the average 1000 times as much as 1 request in one second.
 *
 * <p>This implementation is preserved for backward compatibility (Classic Mode) and for use cases
 * where the exact sequence of events is more important than the chronological time.
 *
 * <p>The mathematical decay formula is:
 * {@code newRate = oldRate * (1 - alpha) + sample * alpha}
 *
 * @since 0.2.0
 */
public final class RequestBasedEwma {

  /**
   * The EWMA smoothing factor (alpha).
   * Controls how much weight each new sample carries in the running average.
   */
  public final double alpha;

  /**
   * Creates a new Request-Based EWMA calculator.
   *
   * @param smoothingFactor The EWMA alpha factor. Clamped to [0.01, 1.0].
   *                        Lower values mean the average reacts more slowly to new samples.
   *                        A value of 1.0 disables smoothing entirely (each sample fully
   *                        overwrites the previous rate).
   */
  public RequestBasedEwma(double smoothingFactor) {
    // Clamping to [0.01, 1.0] to prevent degenerate states like 0.0 (permanently frozen)
    // or > 1.0 (mathematically invalid for EWMA).
    this.alpha = Math.max(0.01, Math.min(1.0, smoothingFactor));
  }

  public double alpha() {
    return alpha;
  }

  /**
   * Calculates the new smoothed value based on a new sample.
   *
   * <p>This method is a pure function and holds no internal state. It is perfectly
   * safe to use inside lock-free CAS (Compare-And-Swap) retry loops.
   *
   * @param currentValue The current smoothed value before this update.
   * @param sample       The new sample value to blend into the average
   *                     (e.g., 0.0 for success, 1.0 for failure).
   * @return The newly calculated smoothed value.
   */
  public double calculate(double currentValue, double sample) {
    return currentValue * (1.0 - alpha) + sample * alpha;
  }
}
