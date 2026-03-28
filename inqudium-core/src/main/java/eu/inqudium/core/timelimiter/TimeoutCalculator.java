package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;

/**
 * Strategy interface for computing the TimeLimiter timeout from a set of
 * HTTP timeout components (ADR-012).
 *
 * <p>For each component {@code tᵢ} the nominal value is assumed to be 50 % of
 * the configured timeout; the tolerance accounts for the remaining 50 %:
 * <pre>
 *   nominal_i   = tᵢ × 0.5
 *   tolerance_i = tᵢ × 0.5
 * </pre>
 *
 * <p>How the individual tolerances are combined into a single value is left to
 * the concrete implementation. The final timeout is always:
 * <pre>
 *   result = (Σ nominal_i + combined_tolerance) × safetyMarginFactor
 * </pre>
 *
 * <p>When {@code components} is empty, every implementation must return the
 * five-second fallback defined by {@link #FALLBACK}.
 *
 * <p>Implementations must be stateless and therefore safe to share across
 * threads and profile instances.
 *
 * @see RssTimeoutCalculator
 * @see WorstCaseTimeoutCalculator
 * @since 0.1.0
 */
public interface TimeoutCalculator {

  /**
   * Fallback returned by every implementation when no components are present.
   */
  Duration FALLBACK = Duration.ofSeconds(5);

  /**
   * Fraction of each component treated as the nominal value.
   */
  double NOMINAL_FRACTION = 0.5;

  /**
   * Fraction of each component treated as the tolerance band.
   */
  double TOLERANCE_FRACTION = 0.5;

  /**
   * Computes the recommended timeout from the given components.
   *
   * @param components         the individual HTTP timeout durations; must not be {@code null}
   * @param safetyMarginFactor multiplier applied after combination (≥ 1.0)
   * @return the computed timeout, never {@code null}
   */
  Duration calculate(Collection<Duration> components, double safetyMarginFactor);
}
