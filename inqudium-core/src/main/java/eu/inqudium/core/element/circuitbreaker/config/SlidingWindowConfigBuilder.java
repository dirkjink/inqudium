package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;

/**
 * Fluent builder for {@link SlidingWindowConfig}.
 *
 * <h2>Purpose</h2>
 * <p>Constructs the configuration for {@link eu.inqudium.core.element.circuitbreaker.metrics.SlidingWindowMetrics},
 * which maintains a fixed-size circular buffer of the last N call outcomes and trips
 * when the failure count within that window reaches a configured threshold.
 *
 * <h2>Configurable Parameters</h2>
 * <ul>
 *   <li>{@code maxFailuresInWindow} — the number of failures within the window that triggers the circuit.</li>
 *   <li>{@code windowSize} — the total number of call outcomes retained in the circular buffer.</li>
 *   <li>{@code minimumNumberOfCalls} — the minimum number of recorded calls before the
 *       threshold is evaluated; must be between 1 and {@code windowSize}.</li>
 * </ul>
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li><strong>{@link #protective()}</strong> — small window (10), low minimum (5), threshold 3;
 *       highly sensitive to even a few failures in a small sample.</li>
 *   <li><strong>{@link #balanced()}</strong> — medium window (50), minimum 20, threshold 15;
 *       standard for most microservices (default).</li>
 *   <li><strong>{@link #permissive()}</strong> — large window (200), minimum 50, threshold 60;
 *       requires significant evidence before tripping.</li>
 * </ul>
 *
 * <h2>Default Behavior</h2>
 * <p>If {@code build()} is called without configuration, the {@link #balanced()} preset is applied.
 */
public class SlidingWindowConfigBuilder extends ExtensionBuilder<SlidingWindowConfig> {

  private Integer maxFailuresInWindow;
  private Integer windowSize;
  private Integer minimumNumberOfCalls;

  /**
   * Sets the failure count threshold within the window.
   *
   * @param maxFailuresInWindow the number of failures that triggers the circuit
   * @return this builder for chaining
   */
  public SlidingWindowConfigBuilder maxFailuresInWindow(int maxFailuresInWindow) {
    this.maxFailuresInWindow = maxFailuresInWindow;
    return this;
  }

  /**
   * Sets the size of the circular buffer (number of most recent outcomes tracked).
   *
   * @param windowSize the buffer capacity (> 0)
   * @return this builder for chaining
   */
  public SlidingWindowConfigBuilder windowSize(int windowSize) {
    this.windowSize = windowSize;
    return this;
  }

  /**
   * Sets the minimum number of recorded outcomes before threshold evaluation is enabled.
   * This prevents premature tripping on insufficient data.
   *
   * @param minimumNumberOfCalls the sample-size guard (1 ≤ value ≤ windowSize)
   * @return this builder for chaining
   */
  public SlidingWindowConfigBuilder minimumNumberOfCalls(int minimumNumberOfCalls) {
    this.minimumNumberOfCalls = minimumNumberOfCalls;
    return this;
  }

  /**
   * <strong>Protective preset</strong> — highly sensitive; a small window with a low
   * threshold catches failures early. Best for critical systems where even a few
   * failures in a short sequence of calls should trip the circuit.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>windowSize</td><td>10</td></tr>
   *   <tr><td>minimumNumberOfCalls</td><td>5</td></tr>
   *   <tr><td>maxFailuresInWindow</td><td>3</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public SlidingWindowConfigBuilder protective() {
    this.windowSize = 10;
    this.minimumNumberOfCalls = 5;
    this.maxFailuresInWindow = 3;
    return this;
  }

  /**
   * <strong>Balanced preset</strong> — standard configuration for most microservices,
   * balancing responsiveness with tolerance for isolated spikes.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>windowSize</td><td>50</td></tr>
   *   <tr><td>minimumNumberOfCalls</td><td>20</td></tr>
   *   <tr><td>maxFailuresInWindow</td><td>15</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public SlidingWindowConfigBuilder balanced() {
    this.windowSize = 50;
    this.minimumNumberOfCalls = 20;
    this.maxFailuresInWindow = 15;
    return this;
  }

  /**
   * <strong>Permissive preset</strong> — high-volume configuration that requires
   * significant history and a high absolute failure count before tripping.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>windowSize</td><td>200</td></tr>
   *   <tr><td>minimumNumberOfCalls</td><td>50</td></tr>
   *   <tr><td>maxFailuresInWindow</td><td>60</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public SlidingWindowConfigBuilder permissive() {
    this.windowSize = 200;
    this.minimumNumberOfCalls = 50;
    this.maxFailuresInWindow = 60;
    return this;
  }

  /**
   * Builds the configuration. Falls back to {@link #balanced()} if any field is unset.
   *
   * @return a fully initialized {@link SlidingWindowConfig}
   */
  public SlidingWindowConfig build() {
    if (minimumNumberOfCalls == null || windowSize == null || maxFailuresInWindow == null) {
      balanced();
    }
    return new SlidingWindowConfig(maxFailuresInWindow, windowSize, minimumNumberOfCalls);
  }
}
