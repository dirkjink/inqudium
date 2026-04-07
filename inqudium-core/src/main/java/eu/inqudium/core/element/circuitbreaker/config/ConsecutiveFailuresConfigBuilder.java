package eu.inqudium.core.element.circuitbreaker.config;

/**
 * Fluent builder for {@link ConsecutiveFailuresConfig}.
 *
 * <h2>Purpose</h2>
 * <p>Constructs the configuration for {@link eu.inqudium.core.element.circuitbreaker.metrics.ConsecutiveFailuresMetrics},
 * which trips the circuit breaker when a specified number of failures occur strictly back-to-back.
 * A single success resets the counter entirely.
 *
 * <h2>Configurable Parameters</h2>
 * <ul>
 *   <li>{@code maxConsecutiveFailures} — the threshold; the circuit opens once this many
 *       uninterrupted failures have been recorded.</li>
 *   <li>{@code initialConsecutiveFailures} — the starting counter value; usually 0, but can
 *       be set higher to pre-load the breaker closer to its trip point (e.g., after a partial
 *       restart where the downstream is known to be degraded).</li>
 * </ul>
 *
 * <h2>Presets</h2>
 * <p>Three opinionated presets are provided. Each one configures <em>all</em> fields so that
 * calling {@code build()} afterwards requires no additional setter calls:
 * <ul>
 *   <li><strong>{@link #protective()}</strong> — low tolerance; trips after just 4 consecutive failures.</li>
 *   <li><strong>{@link #balanced()}</strong> — moderate tolerance; trips after 15 consecutive failures (default).</li>
 *   <li><strong>{@link #permissive()}</strong> — high tolerance; trips after 50 consecutive failures.</li>
 * </ul>
 *
 * <h2>Default Behavior</h2>
 * <p>If {@code build()} is called without setting any fields or selecting a preset,
 * the {@link #balanced()} preset is applied automatically.
 */
public class ConsecutiveFailuresConfigBuilder {

  private Integer maxConsecutiveFailures;
  private Integer initialConsecutiveFailures;

  /**
   * Sets the starting failure counter. Typically 0, but can be pre-loaded
   * to a higher value for advanced scenarios (e.g., warm-start after deployment
   * against a known-degraded dependency).
   *
   * @param count the initial consecutive failure count (>= 0)
   * @return this builder for chaining
   */
  public ConsecutiveFailuresConfigBuilder initialConsecutiveFailures(int count) {
    this.initialConsecutiveFailures = count;
    return this;
  }

  /**
   * Sets the consecutive failure threshold. Once this many back-to-back failures
   * are recorded, {@code isThresholdReached()} returns {@code true}.
   *
   * @param maxConsecutiveFailures the trip threshold (> 0)
   * @return this builder for chaining
   */
  public ConsecutiveFailuresConfigBuilder maxConsecutiveFailures(int maxConsecutiveFailures) {
    this.maxConsecutiveFailures = maxConsecutiveFailures;
    return this;
  }

  /**
   * <strong>Protective preset</strong> — high sensitivity for critical, low-latency dependencies
   * where even a short burst of consecutive errors likely indicates a complete outage.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>maxConsecutiveFailures</td><td>4</td></tr>
   *   <tr><td>initialConsecutiveFailures</td><td>0</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public ConsecutiveFailuresConfigBuilder protective() {
    this.maxConsecutiveFailures = 4;
    this.initialConsecutiveFailures = 0;
    return this;
  }

  /**
   * <strong>Balanced preset</strong> — moderate sensitivity suitable for typical distributed
   * systems where small network hiccups should be tolerated, but sustained connectivity
   * loss must be detected.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>maxConsecutiveFailures</td><td>15</td></tr>
   *   <tr><td>initialConsecutiveFailures</td><td>0</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public ConsecutiveFailuresConfigBuilder balanced() {
    this.maxConsecutiveFailures = 15;
    this.initialConsecutiveFailures = 0;
    return this;
  }

  /**
   * <strong>Permissive preset</strong> — very high tolerance for unstable legacy systems or
   * non-critical background tasks that should almost never trigger the breaker.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>maxConsecutiveFailures</td><td>50</td></tr>
   *   <tr><td>initialConsecutiveFailures</td><td>0</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public ConsecutiveFailuresConfigBuilder permissive() {
    this.maxConsecutiveFailures = 50;
    this.initialConsecutiveFailures = 0;
    return this;
  }

  /**
   * Builds the configuration. If any required field is still {@code null},
   * the {@link #balanced()} preset is applied first as a safe default.
   *
   * @return a fully initialized {@link ConsecutiveFailuresConfig}
   */
  public ConsecutiveFailuresConfig build() {
    if (maxConsecutiveFailures == null || initialConsecutiveFailures == null) {
      balanced();
    }
    return new ConsecutiveFailuresConfig(maxConsecutiveFailures, initialConsecutiveFailures);
  }
}
