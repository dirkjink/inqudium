package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;

/**
 * Strategy interface for computing the TimeLimiter timeout from a set of
 * HTTP timeout components (ADR-012).
 *
 * <p>Each configured timeout component {@code tᵢ} is treated as the full
 * tolerance — i.e. the upper bound that component is allowed to consume.
 * How those individual tolerances are combined into a single result is left
 * to the concrete implementation.
 *
 * <p>When {@code components} is empty, every implementation must return the
 * five-second fallback defined by {@link #FALLBACK}.
 *
 * <p>Implementations must be stateless and therefore safe to share across
 * threads and profile instances.
 *
 * @see RssTimeoutCalculator
 * @see WorstCaseTimeoutCalculator
 * @see MaxTimeoutCalculator
 * @since 0.1.0
 */
public interface TimeoutCalculator {

  /**
   * Fallback returned by every implementation when no components are present.
   */
  Duration FALLBACK = Duration.ofSeconds(5);

  /**
   * Computes the recommended timeout from the given components.
   *
   * @param components         the individual HTTP timeout durations; must not be {@code null}
   * @param safetyMarginFactor multiplier applied after combination (≥ 1.0)
   * @return the computed timeout, never {@code null}
   */
  Duration calculate(Collection<Duration> components, double safetyMarginFactor);
}
