package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;

/**
 * Fluent builder for {@link LeakyBucketConfig}.
 *
 * <h2>Purpose</h2>
 * <p>Constructs the configuration for {@link eu.inqudium.core.element.circuitbreaker.metrics.LeakyBucketMetrics},
 * which models failures as water pouring into a bucket that leaks at a constant rate.
 * The circuit trips when the bucket's water level reaches its capacity.
 *
 * <h2>Configurable Parameters</h2>
 * <ul>
 *   <li>{@code bucketCapacity} — the maximum water level (threshold); when the level
 *       reaches or exceeds this value, the circuit opens.</li>
 *   <li>{@code leakRatePerSecond} — how many units of water drain per second; controls
 *       how quickly past failures are "forgotten" over time.</li>
 * </ul>
 *
 * <h2>Tuning Guidance</h2>
 * <p>The interplay between capacity and leak rate determines burst tolerance:
 * <ul>
 *   <li><em>Low capacity + slow leak</em> → very sensitive; a small burst fills the bucket.</li>
 *   <li><em>High capacity + fast leak</em> → tolerant; only sustained high-frequency failures overflow.</li>
 * </ul>
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li><strong>{@link #protective()}</strong> — small bucket (5), slow leak (0.1/s);
 *       trips quickly on even modest failure bursts.</li>
 *   <li><strong>{@link #balanced()}</strong> — medium bucket (10), moderate leak (1.0/s);
 *       tolerates occasional failures while catching sustained errors (default).</li>
 *   <li><strong>{@link #permissive()}</strong> — large bucket (30), fast leak (5.0/s);
 *       requires a high-frequency error stream to overflow.</li>
 * </ul>
 *
 * <h2>Default Behavior</h2>
 * <p>If {@code build()} is called without configuration, the {@link #balanced()} preset is applied.
 */
public class LeakyBucketConfigBuilder extends ExtensionBuilder<LeakyBucketConfig> {

  private Integer bucketCapacity;
  private Double leakRatePerSecond;

  /**
   * Sets the bucket capacity (threshold). The circuit opens when the water level
   * reaches or exceeds this value.
   *
   * @param bucketCapacity the bucket capacity (> 0)
   * @return this builder for chaining
   */
  public LeakyBucketConfigBuilder bucketCapacity(int bucketCapacity) {
    this.bucketCapacity = bucketCapacity;
    return this;
  }

  /**
   * Sets the rate at which the bucket drains, in units per second.
   * A higher leak rate means failures are forgotten faster.
   *
   * @param rate the leak rate (≥ 0)
   * @return this builder for chaining
   */
  public LeakyBucketConfigBuilder leakRatePerSecond(double rate) {
    this.leakRatePerSecond = rate;
    return this;
  }

  /**
   * <strong>Protective preset</strong> — small bucket with a slow leak; trips very fast
   * on failure bursts because the bucket fills quickly and drains slowly. Best for
   * critical dependencies where even a brief burst of errors is concerning.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>bucketCapacity</td><td>5</td></tr>
   *   <tr><td>leakRatePerSecond</td><td>0.1</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public LeakyBucketConfigBuilder protective() {
    this.bucketCapacity = 5;
    this.leakRatePerSecond = 0.1;
    return this;
  }

  /**
   * <strong>Balanced preset</strong> — medium bucket with moderate leak; allows a steady
   * stream of occasional failures while preventing sustained errors from going undetected.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>bucketCapacity</td><td>10</td></tr>
   *   <tr><td>leakRatePerSecond</td><td>1.0</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public LeakyBucketConfigBuilder balanced() {
    this.bucketCapacity = 10;
    this.leakRatePerSecond = 1.0;
    return this;
  }

  /**
   * <strong>Permissive preset</strong> — large bucket with fast leak; requires a
   * high-frequency error stream to overflow. Best for non-critical services or
   * dependencies known to produce intermittent transient errors.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>bucketCapacity</td><td>30</td></tr>
   *   <tr><td>leakRatePerSecond</td><td>5.0</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public LeakyBucketConfigBuilder permissive() {
    this.bucketCapacity = 30;
    this.leakRatePerSecond = 5.0;
    return this;
  }

  /**
   * Builds the configuration. Falls back to {@link #balanced()} if any field is unset.
   *
   * @return a fully initialized {@link LeakyBucketConfig}
   */
  public LeakyBucketConfig build() {
    if (bucketCapacity == null || leakRatePerSecond == null) {
      balanced();
    }
    return new LeakyBucketConfig(bucketCapacity, leakRatePerSecond);
  }
}
