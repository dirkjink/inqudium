package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

/**
 * {@link TimeoutCalculator} implementation for the RSS (Root Sum of Squares) strategy.
 *
 * <p>Combines the individual tolerance bands as a quadratic sum scaled by a
 * configurable {@link SigmaLevel}:
 * <pre>
 *   combined_tolerance = sigmaLevel × √(tolerance_1² + tolerance_2² + … + tolerance_n²)
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

  /** Applied as multiplier to the raw RSS tolerance. */
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
   * <p>Accumulates the squared tolerances of all components, takes the square root
   * to obtain the 1σ RSS tolerance, scales it by the configured {@link SigmaLevel},
   * and then applies the safety margin factor.
   */
  @Override
  public Duration calculate(Collection<Duration> components, double safetyMarginFactor) {
    if (components.isEmpty()) {
      return FALLBACK;
    }

    double nominalSumMs = 0.0;
    double squaredToleranceSum = 0.0;

    for (Duration component : components) {
      double ms = component.toMillis();
      nominalSumMs += ms * NOMINAL_FRACTION;
      double tolerance = ms * TOLERANCE_FRACTION;
      squaredToleranceSum += tolerance * tolerance; // accumulate squared values for √ below
    }

    // Scale the 1σ RSS result by the chosen sigma level multiplier
    double combinedTolerance = sigmaLevel.multiplier() * Math.sqrt(squaredToleranceSum);
    double totalMs = (nominalSumMs + combinedTolerance) * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }
}
