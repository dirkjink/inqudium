package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;

/**
 * Computes the TimeLimiter timeout from a set of HTTP timeout components (ADR-012).
 *
 * <p>Extracted from {@link InqTimeoutProfile} to keep the profile a pure data holder
 * and to allow the algorithm to be tested and reused independently.
 *
 * <h2>Algorithm</h2>
 * <p>For each component {@code tᵢ} the nominal value is assumed to be 50 % of the
 * configured timeout; the tolerance accounts for the remaining 50 %:
 * <pre>
 *   nominal_i   = tᵢ × 0.5
 *   tolerance_i = tᵢ × 0.5
 * </pre>
 *
 * <p>The tolerances are combined according to the chosen {@link TimeoutCalculation}:
 * <pre>
 *   RSS        →  combined = √(Σ tolerance_i²)
 *   WORST_CASE →  combined =   Σ tolerance_i
 * </pre>
 *
 * <p>The final timeout is:
 * <pre>
 *   result = (Σ nominal_i + combined_tolerance) × safetyMarginFactor
 * </pre>
 *
 * @since 0.1.0
 */
public final class TimeoutCalculator {

  /** Fallback returned when no components are present. */
  private static final Duration FALLBACK = Duration.ofSeconds(5);

  /** Fraction of each component treated as the nominal value. */
  private static final double NOMINAL_FRACTION = 0.5;

  /** Fraction of each component treated as the tolerance band. */
  private static final double TOLERANCE_FRACTION = 0.5;

  /**
   * Computes the recommended timeout from the given components.
   *
   * @param components       the individual HTTP timeout durations; must not be {@code null}
   * @param method           RSS or WORST_CASE combination strategy; must not be {@code null}
   * @param safetyMarginFactor multiplier applied after combination (≥ 1.0); must not be {@code null}
   * @return the computed timeout, never {@code null}
   */
  public Duration calculate(
      Collection<Duration> components,
      TimeoutCalculation method,
      double safetyMarginFactor) {

    if (components.isEmpty()) {
      return FALLBACK;
    }

    double nominalSumMs = 0.0;
    double combinedToleranceInput = 0.0;

    for (Duration component : components) {
      double ms = component.toMillis();
      nominalSumMs += ms * NOMINAL_FRACTION;
      double tolerance = ms * TOLERANCE_FRACTION;

      // Accumulate tolerance according to the chosen strategy
      combinedToleranceInput += switch (method) {
        case RSS -> tolerance * tolerance; // accumulate squared values for √ later
        case WORST_CASE -> tolerance;      // accumulate linearly
      };
    }

    double combinedTolerance = switch (method) {
      case RSS -> Math.sqrt(combinedToleranceInput);
      case WORST_CASE -> combinedToleranceInput;
    };

    double totalMs = (nominalSumMs + combinedTolerance) * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }
}
