package eu.inqudium.core.timelimiter;

/**
 * Sigma level (standard-deviation multiplier) used by {@link RssTimeoutCalculator}
 * to control how much of the statistical tolerance band is covered by the computed
 * timeout (ADR-012).
 *
 * <p>In a normal distribution the RSS tolerance represents one standard deviation (1σ).
 * Multiplying it by {@code n} widens the timeout window to cover the fraction of
 * requests that fall within ±nσ of the mean:
 *
 * <pre>
 *   timeout_tolerance = n × √(tolerance_1² + tolerance_2² + … + tolerance_n²)
 * </pre>
 *
 * <h2>Choosing the right level</h2>
 * <table border="1" cellpadding="5" cellspacing="0">
 * <tr><th>Level</th><th>Coverage</th><th>Recommended use case</th></tr>
 * <tr>
 *   <td>{@link #ONE_SIGMA}</td><td>68.27 %</td>
 *   <td>Adapters with a fast fail-over (e.g. cache fallback) where aggressive
 *       timeout behaviour is explicitly desired.</td>
 * </tr>
 * <tr>
 *   <td>{@link #TWO_SIGMA}</td><td>95.45 %</td>
 *   <td><b>Default.</b> Suitable for most adapters. Aggressive enough to avoid
 *       blocking Tomcat threads unnecessarily; the Circuit Breaker absorbs the
 *       remaining ~4.5 % of statistical outliers.</td>
 * </tr>
 * <tr>
 *   <td>{@link #THREE_SIGMA}</td><td>99.73 %</td>
 *   <td>Critical adapters where a timeout is expensive — e.g.
 *       {@code PermissionsApiReactiveAdapter}, where a failed call means the
 *       customer sees no products at all.</td>
 * </tr>
 * </table>
 *
 * @since 0.1.0
 */
public enum SigmaLevel {

  /**
   * 1σ — covers 68.27 % of requests.
   *
   * <p>Only advisable when a fast fail-over path exists (e.g. a warm local cache)
   * and aggressive timeouts are explicitly desired to shed load quickly.
   */
  ONE_SIGMA(1.0, 68.27),

  /**
   * 2σ — covers 95.45 % of requests.
   *
   * <p>The recommended default for most adapters. Balances thread utilisation
   * against the risk of prematurely aborting valid (but slow) requests. The
   * Circuit Breaker handles the statistical outliers that exceed this window.
   */
  TWO_SIGMA(2.0, 95.45),

  /**
   * 3σ — covers 99.73 % of requests.
   *
   * <p>For critical adapters where a timeout carries a high business cost, such
   * as permission or entitlement services whose failure degrades the entire
   * customer experience.
   */
  THREE_SIGMA(3.0, 99.73);

  private final double multiplier;
  private final double coveragePercent;

  SigmaLevel(double multiplier, double coveragePercent) {
    this.multiplier = multiplier;
    this.coveragePercent = coveragePercent;
  }

  /**
   * Returns the standard-deviation multiplier applied to the RSS tolerance.
   *
   * @return {@code 1.0}, {@code 2.0}, or {@code 3.0}
   */
  public double multiplier() {
    return multiplier;
  }

  /**
   * Returns the approximate percentage of requests covered within this
   * sigma window, assuming a normal distribution.
   *
   * @return coverage in percent (e.g. {@code 95.45} for {@link #TWO_SIGMA})
   */
  public double coveragePercent() {
    return coveragePercent;
  }
}
