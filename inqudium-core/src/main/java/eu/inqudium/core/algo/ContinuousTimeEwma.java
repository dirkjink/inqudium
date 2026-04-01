package eu.inqudium.core.algo;

import java.time.Duration;

/**
 * A stateless calculator for a Continuous-Time Exponentially Weighted Moving Average (EWMA).
 *
 * <p>Unlike a standard request-based EWMA, which decays per event and artificially
 * accelerates under high throughput, a continuous-time EWMA decays based on the exact
 * elapsed time. This guarantees that the metric decays at a consistent chronological
 * speed, making it completely independent of the Requests-Per-Second (RPS).
 *
 * <p>The mathematical decay factor is calculated as:
 * {@code decay = e^(-deltaNanos / tauNanos)}
 *
 * @since 0.2.0
 */
public final class ContinuousTimeEwma {

  /**
   * The time constant (Tau) in nanoseconds. Controls how quickly the value decays.
   */
  private final long tauNanos;

  /**
   * Creates a new Continuous-Time EWMA calculator.
   *
   * @param timeConstant The time constant (Tau). A larger duration means the average
   *                     reacts more slowly and ignores short spikes. Must be at least
   *                     1 nanosecond to prevent division by zero.
   */
  public ContinuousTimeEwma(Duration timeConstant) {
    this.tauNanos = Math.max(1L, timeConstant.toNanos());
  }

  public long tauDurationNanos() {
    return tauNanos;
  }

  /**
   * Calculates the new smoothed value based on the elapsed time and a new sample.
   *
   * <p>This method is a pure function and holds no internal state. This makes it
   * perfectly safe to use inside lock-free CAS (Compare-And-Swap) retry loops, as
   * no state is corrupted if the loop needs to retry.
   *
   * @param currentValue    The current smoothed value before this update.
   * @param lastUpdateNanos The timestamp (in nanoseconds) of the last state update.
   * @param nowNanos        The current timestamp (in nanoseconds).
   * @param sample          The new sample value (e.g., 0.0 for success, 1.0 for failure)
   *                        to blend into the average.
   * @return The newly calculated, time-decayed smoothed value.
   */
  public double calculate(double currentValue, long lastUpdateNanos, long nowNanos, double sample) {
    // Math.max protects against clock skew or backward nanoTime anomalies.
    long deltaNanos = Math.max(0, nowNanos - lastUpdateNanos);

    // The time-based exponential decay formula: e^(-delta / tau)
    double decay = Math.exp(-deltaNanos / (double) tauNanos);
    double alpha = 1.0 - decay;

    return currentValue * decay + sample * alpha;
  }
}