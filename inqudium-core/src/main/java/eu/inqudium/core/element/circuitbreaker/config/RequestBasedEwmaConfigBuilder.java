package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;

/**
 * Fluent builder for {@link RequestBasedEwmaConfig}.
 *
 * <h2>Purpose</h2>
 * <p>Constructs the configuration for {@link eu.inqudium.core.element.circuitbreaker.metrics.RequestBasedEwmaMetrics},
 * which computes a smoothed failure rate using an Exponentially Weighted Moving Average
 * that decays per request rather than by wall-clock time.
 *
 * <h2>Configurable Parameters</h2>
 * <ul>
 *   <li>{@code failureRatePercent} — the percentage threshold (1–100); the circuit trips
 *       when the EWMA failure rate reaches or exceeds this value.</li>
 *   <li>{@code smoothingFactor} (alpha) — controls reactivity; values closer to 1.0 give
 *       more weight to the most recent observation, while values closer to 0.0 produce
 *       a more stable, slowly changing average.</li>
 *   <li>{@code minimumNumberOfCalls} — minimum samples before the threshold is evaluated,
 *       preventing premature tripping on small sample sizes.</li>
 * </ul>
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li><strong>{@link #protective()}</strong> — high alpha (0.5), low minimum (5), threshold 30%;
 *       new failures immediately dominate the average.</li>
 *   <li><strong>{@link #balanced()}</strong> — moderate alpha (0.2), medium minimum (10), threshold 50%;
 *       filters out noise while tracking trends (default).</li>
 *   <li><strong>{@link #permissive()}</strong> — low alpha (0.05), high minimum (50), threshold 70%;
 *       very resilient to short-term spikes.</li>
 * </ul>
 *
 * <h2>Default Behavior</h2>
 * <p>If {@code build()} is called without configuration, the {@link #balanced()} preset is applied.
 */
public class RequestBasedEwmaConfigBuilder extends ExtensionBuilder<RequestBasedEwmaConfig> {

    private Double failureRatePercent;
    private Double smoothingFactor;
    private Integer minimumNumberOfCalls;

    /**
     * Sets the failure rate percentage threshold. The circuit trips when the EWMA
     * failure rate reaches or exceeds this value (interpreted as a percentage, 1–100).
     *
     * @param failureRatePercent the threshold percentage (e.g., 50.0 for 50%)
     * @return this builder for chaining
     */
    public RequestBasedEwmaConfigBuilder failureRatePercent(double failureRatePercent) {
        this.failureRatePercent = failureRatePercent;
        return this;
    }

    /**
     * Sets the EWMA smoothing factor (alpha). Higher values make the average more
     * reactive to recent observations; lower values make it more stable.
     *
     * @param alpha the smoothing factor (0 &lt; alpha ≤ 1)
     * @return this builder for chaining
     */
    public RequestBasedEwmaConfigBuilder smoothingFactor(double alpha) {
        this.smoothingFactor = alpha;
        return this;
    }

    /**
     * Sets the minimum number of recorded call outcomes before the threshold is evaluated.
     *
     * @param calls the sample-size guard (> 0)
     * @return this builder for chaining
     */
    public RequestBasedEwmaConfigBuilder minimumNumberOfCalls(int calls) {
        this.minimumNumberOfCalls = calls;
        return this;
    }

    /**
     * <strong>Protective preset</strong> — high alpha ensures new failures immediately
     * dominate the average. Combined with a low threshold, this catches degradation
     * aggressively.
     *
     * <table>
     *   <tr><th>Parameter</th><th>Value</th></tr>
     *   <tr><td>failureRatePercent</td><td>30.0</td></tr>
     *   <tr><td>smoothingFactor (alpha)</td><td>0.5</td></tr>
     *   <tr><td>minimumNumberOfCalls</td><td>5</td></tr>
     * </table>
     *
     * @return this builder for chaining
     */
    public RequestBasedEwmaConfigBuilder protective() {
        this.failureRatePercent = 30.0;
        this.smoothingFactor = 0.5;
        this.minimumNumberOfCalls = 5;
        return this;
    }

    /**
     * <strong>Balanced preset</strong> — moderate smoothing that filters out noise while
     * still tracking recent trends. Suitable for most microservice-to-microservice calls.
     *
     * <table>
     *   <tr><th>Parameter</th><th>Value</th></tr>
     *   <tr><td>failureRatePercent</td><td>50.0</td></tr>
     *   <tr><td>smoothingFactor (alpha)</td><td>0.2</td></tr>
     *   <tr><td>minimumNumberOfCalls</td><td>10</td></tr>
     * </table>
     *
     * @return this builder for chaining
     */
    public RequestBasedEwmaConfigBuilder balanced() {
        this.failureRatePercent = 50.0;
        this.smoothingFactor = 0.2;
        this.minimumNumberOfCalls = 10;
        return this;
    }

    /**
     * <strong>Permissive preset</strong> — low alpha makes the metric very resilient to
     * short-term changes. Combined with a high threshold and minimum calls, this is
     * suited for non-critical or noisy dependencies.
     *
     * <table>
     *   <tr><th>Parameter</th><th>Value</th></tr>
     *   <tr><td>failureRatePercent</td><td>70.0</td></tr>
     *   <tr><td>smoothingFactor (alpha)</td><td>0.05</td></tr>
     *   <tr><td>minimumNumberOfCalls</td><td>50</td></tr>
     * </table>
     *
     * @return this builder for chaining
     */
    public RequestBasedEwmaConfigBuilder permissive() {
        this.failureRatePercent = 70.0;
        this.smoothingFactor = 0.05;
        this.minimumNumberOfCalls = 50;
        return this;
    }

    /**
     * Builds the configuration. Falls back to {@link #balanced()} if any field is unset.
     *
     * @return a fully initialized {@link RequestBasedEwmaConfig}
     */
    public RequestBasedEwmaConfig build() {
        if (minimumNumberOfCalls == null || smoothingFactor == null || failureRatePercent == null) {
            balanced();
        }
        return new RequestBasedEwmaConfig(failureRatePercent, smoothingFactor, minimumNumberOfCalls);
    }
}
