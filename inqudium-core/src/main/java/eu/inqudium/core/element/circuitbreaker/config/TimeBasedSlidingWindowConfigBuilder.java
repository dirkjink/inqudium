package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;

/**
 * Fluent builder for {@link TimeBasedSlidingWindowConfig}.
 *
 * <h2>Purpose</h2>
 * <p>Constructs the configuration for {@link eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedSlidingWindowMetrics},
 * which tracks failures in a rolling time window of 1-second buckets and trips when the
 * total failure count across the window reaches a configured threshold.
 *
 * <h2>Configurable Parameters</h2>
 * <ul>
 *   <li>{@code maxFailuresInWindow} — the absolute failure count threshold; the circuit
 *       opens when the sum of all bucket counts reaches or exceeds this value.</li>
 *   <li>{@code windowSizeInSeconds} — the duration of the sliding window (and the number
 *       of 1-second buckets).</li>
 * </ul>
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li><strong>{@link #protective()}</strong> — tiny 5-second window, threshold 3;
 *       detects immediate, hard outages within seconds.</li>
 *   <li><strong>{@link #balanced()}</strong> — standard 60-second window, threshold 10;
 *       good balance between responsiveness and history (default).</li>
 *   <li><strong>{@link #permissive()}</strong> — long 300-second (5-minute) window, threshold 30;
 *       tolerant of intermittent blips and high recovery times.</li>
 * </ul>
 *
 * <h2>Default Behavior</h2>
 * <p>If {@code build()} is called without configuration, the {@link #balanced()} preset is applied.
 */
public class TimeBasedSlidingWindowConfigBuilder extends ExtensionBuilder<TimeBasedSlidingWindowConfig> {

  private Integer maxFailuresInWindow;
  private Integer windowSizeInSeconds;

  /**
   * Sets the failure count threshold. The circuit opens when the total number of
   * failures across all active time buckets reaches or exceeds this value.
   *
   * @param maxFailuresInWindow the failure threshold (> 0)
   * @return this builder for chaining
   */
  public TimeBasedSlidingWindowConfigBuilder maxFailuresInWindow(int maxFailuresInWindow) {
    this.maxFailuresInWindow = maxFailuresInWindow;
    return this;
  }

  /**
   * Sets the window duration in seconds. This also determines the number of
   * 1-second buckets maintained internally.
   *
   * @param seconds the window size (> 0)
   * @return this builder for chaining
   */
  public TimeBasedSlidingWindowConfigBuilder windowSizeInSeconds(int seconds) {
    this.windowSizeInSeconds = seconds;
    return this;
  }

  /**
   * <strong>Protective preset</strong> — very strict window that only considers the
   * last 5 seconds. Useful for detecting immediate, hard outages of critical
   * low-latency dependencies.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>windowSizeInSeconds</td><td>5</td></tr>
   *   <tr><td>maxFailuresInWindow</td><td>3</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public TimeBasedSlidingWindowConfigBuilder protective() {
    this.windowSizeInSeconds = 5;
    this.maxFailuresInWindow = 3;
    return this;
  }

  /**
   * <strong>Balanced preset</strong> — standard 1-minute window providing a good balance
   * between responsiveness and history.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>windowSizeInSeconds</td><td>60</td></tr>
   *   <tr><td>maxFailuresInWindow</td><td>10</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public TimeBasedSlidingWindowConfigBuilder balanced() {
    this.windowSizeInSeconds = 60;
    this.maxFailuresInWindow = 10;
    return this;
  }

  /**
   * <strong>Permissive preset</strong> — long 5-minute window for systems where high
   * recovery times or intermittent blips are expected and should not trip the breaker easily.
   *
   * <table>
   *   <tr><th>Parameter</th><th>Value</th></tr>
   *   <tr><td>windowSizeInSeconds</td><td>300</td></tr>
   *   <tr><td>maxFailuresInWindow</td><td>30</td></tr>
   * </table>
   *
   * @return this builder for chaining
   */
  public TimeBasedSlidingWindowConfigBuilder permissive() {
    this.windowSizeInSeconds = 300;
    this.maxFailuresInWindow = 30;
    return this;
  }

  /**
   * Builds the configuration. Falls back to {@link #balanced()} if any field is unset.
   *
   * @return a fully initialized {@link TimeBasedSlidingWindowConfig}
   */
  public TimeBasedSlidingWindowConfig build() {
    if (windowSizeInSeconds == null || maxFailuresInWindow == null) {
      balanced();
    }
    return new TimeBasedSlidingWindowConfig(maxFailuresInWindow, windowSizeInSeconds);
  }
}
