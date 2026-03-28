package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;

/**
 * {@link TimeoutCalculator} implementation for the worst-case (linear sum) strategy.
 *
 * <p>Treats each configured timeout component as its full tolerance and sums
 * them linearly:
 * <pre>
 *   result = (t₁ + t₂ + … + tₙ) × safetyMarginFactor
 * </pre>
 *
 * <p>Equivalent to assuming every component simultaneously hits its maximum
 * value. Produces very conservative (larger) timeouts than RSS.
 * Use when timeout components are sequentially dependent (e.g. retry attempts)
 * or when a conservative upper bound is preferred over a statistical estimate
 * (ADR-012).
 *
 * @see RssTimeoutCalculator
 * @since 0.1.0
 */
public final class WorstCaseTimeoutCalculator implements TimeoutCalculator {

  /**
   * {@inheritDoc}
   *
   * <p>Computes {@code (Σtᵢ) × safetyMarginFactor}, where each {@code tᵢ} is
   * the full millisecond value of the corresponding timeout component.
   */
  @Override
  public Duration calculate(Collection<Duration> components, double safetyMarginFactor) {
    if (components.isEmpty()) {
      return FALLBACK;
    }

    double sumMs = 0.0;
    for (Duration component : components) {
      sumMs += component.toMillis(); // accumulate linearly
    }

    double totalMs = sumMs * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }
}
