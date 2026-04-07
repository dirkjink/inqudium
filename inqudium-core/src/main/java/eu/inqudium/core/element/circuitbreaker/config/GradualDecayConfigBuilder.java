package eu.inqudium.core.element.circuitbreaker.config;

/**
 * Fluent builder for {@link GradualDecayConfig}.
 *
 * <h2>Purpose</h2>
 * <p>Constructs the configuration for {@link eu.inqudium.core.element.circuitbreaker.metrics.GradualDecayMetrics},
 * where each failure increments a counter and each success decrements it by one (floored at zero).
 * The circuit trips when the counter reaches {@code maxFailureCount}.
 *
 * <h2>Configurable Parameters</h2>
 * <ul>
 *   <li>{@code maxFailureCount} — the net failure count threshold at which the circuit opens.</li>
 *   <li>{@code initialFailureCount} — the starting counter value; usually 0.</li>
 * </ul>
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li><strong>{@link #protective()}</strong> — low threshold (7); a small imbalance of
 *       failures over successes trips the circuit quickly.</li>
 *   <li><strong>{@link #balanced()}</strong> — moderate threshold (37); tolerates occasional
 *       noise but catches sustained degradation (default).</li>
 *   <li><strong>{@link #permissive()}</strong> — high threshold (100); suited for systems
 *       where intermittent failures are common and slow recovery is acceptable.</li>
 * </ul>
 *
 * <h2>Default Behavior</h2>
 * <p>If {@code build()} is called without configuration, the {@link #balanced()} preset is applied.
 */
public class GradualDecayConfigBuilder {

  private Integer maxFailureCount;
  private Integer initialFailureCount;

  /**
   * Sets the failure count threshold. The circuit opens when the net failure count
   * (failures minus healed-by-successes) reaches this value.
   *
   * @param maxFailureCount the trip threshold (> 0)
   * @return this builder for chaining
   */
  public GradualDecayConfigBuilder maxFailureCount(int maxFailureCount) {
    this.maxFailureCount = maxFailureCount;
    return this;
  }

  /**
   * <strong>Protective preset</strong> — high sensitivity; even a modest imbalance of
   * failures over successes trips the circuit. Best for critical dependencies where
   * recurring issues must be caught early.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>maxFailureCount</td><td>7</td></tr>
   *   <tr><td>initialFailureCount</td><td>0</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public GradualDecayConfigBuilder protective() {
    this.maxFailureCount = 7;
    this.initialFailureCount = 0;
    return this;
  }

  /**
   * <strong>Balanced preset</strong> — tolerates occasional noise while detecting
   * sustained degradation. A steady stream of successes is needed to heal accumulated failures.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>maxFailureCount</td><td>37</td></tr>
   *   <tr><td>initialFailureCount</td><td>0</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public GradualDecayConfigBuilder balanced() {
    this.maxFailureCount = 37;
    this.initialFailureCount = 0;
    return this;
  }

  /**
   * <strong>Permissive preset</strong> — high capacity for errors; intended for systems
   * where intermittent failures are common and slow recovery is acceptable.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>maxFailureCount</td><td>100</td></tr>
   *   <tr><td>initialFailureCount</td><td>0</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public GradualDecayConfigBuilder permissive() {
    this.maxFailureCount = 100;
    this.initialFailureCount = 0;
    return this;
  }

  /**
   * Builds the configuration. Falls back to {@link #balanced()} if any field is unset.
   *
   * @return a fully initialized {@link GradualDecayConfig}
   */
  public GradualDecayConfig build() {
    if (maxFailureCount == null || initialFailureCount == null) {
      balanced();
    }
    return new GradualDecayConfig(maxFailureCount, initialFailureCount);
  }
}
