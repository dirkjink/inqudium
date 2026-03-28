package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

/**
 * {@link TimeoutCalculator} implementation for the RSS (Root Sum of Squares) strategy.
 *
 * <p>Treats each configured timeout component as its full tolerance and combines
 * them as a quadratic sum scaled by a configurable {@link SigmaLevel}:
 * <pre>
 *   result = σ × √(t₁² + t₂² + … + tₙ²) × safetyMarginFactor
 * </pre>
 *
 * <p>The sigma level controls what fraction of requests the computed timeout
 * covers statistically:
 * <ul>
 *   <li>{@link SigmaLevel#ONE_SIGMA} (1σ) — 68.27 %; aggressive, for adapters with fast fail-over.</li>
 *   <li>{@link SigmaLevel#TWO_SIGMA} (2σ) — 95.45 %; <b>default</b>, suitable for most adapters.</li>
 *   <li>{@link SigmaLevel#THREE_SIGMA} (3σ) — 99.73 %; conservative, for critical adapters.</li>
 * </ul>
 *
 * <p>Produces tighter, more realistic timeout budgets than worst-case addition
 * because it accounts for the statistical independence of individual delays.
 * Use when the timeout components are independent — the common case and the
 * recommended default (ADR-012).
 *
 * @see SigmaLevel
 * @see WorstCaseTimeoutCalculator
 * @since 0.1.0
 */
public final class RssTimeoutCalculator implements TimeoutCalculator {

  /** Applied as multiplier to the raw RSS value. */
  private final SigmaLevel sigmaLevel;

  /**
   * Creates an RSS calculator with the default sigma level ({@link SigmaLevel#TWO_SIGMA}).
   */
  public RssTimeoutCalculator() {
    this(SigmaLevel.TWO_SIGMA);
  }

  /**
   * Creates an RSS calculator with the given sigma level.
   *
   * @param sigmaLevel the sigma level to apply; must not be {@code null}
   */
  public RssTimeoutCalculator(SigmaLevel sigmaLevel) {
    this.sigmaLevel = Objects.requireNonNull(sigmaLevel, "sigmaLevel must not be null");
  }

  /**
   * Returns the sigma level used by this calculator.
   *
   * @return the active {@link SigmaLevel}
   */
  public SigmaLevel getSigmaLevel() {
    return sigmaLevel;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Computes {@code σ × √(Σtᵢ²) × safetyMarginFactor}, where each {@code tᵢ}
   * is the full millisecond value of the corresponding timeout component.
   */
  @Override
  public Duration calculate(Collection<Duration> components, double safetyMarginFactor) {
    if (components.isEmpty()) {
      return FALLBACK;
    }

    double squaredSum = 0.0;
    for (Duration component : components) {
      double ms = component.toMillis();
      squaredSum += Math.pow(ms, 2);
    }

    double totalMs = sigmaLevel.multiplier() * Math.sqrt(squaredSum) * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }
}
