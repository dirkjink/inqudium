package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.time.Duration;

/**
 * Fluent builder for {@link ContinuousTimeEwmaConfig}.
 *
 * <h2>Purpose</h2>
 * <p>Constructs the configuration for {@link eu.inqudium.core.element.circuitbreaker.metrics.ContinuousTimeEwmaMetrics},
 * which computes a smoothed failure rate using a Continuous-Time EWMA that decays based
 * on elapsed wall-clock time rather than request count.
 *
 * <h2>Configurable Parameters</h2>
 * <ul>
 *   <li>{@code failureRatePercent} — the percentage threshold (1–100); the circuit trips
 *       when the time-decayed EWMA rate reaches or exceeds this value.</li>
 *   <li>{@code timeConstant} (tau) — a {@link Duration} controlling how quickly past
 *       observations fade. After one tau has elapsed, a past observation retains ≈36.8%
 *       (1/e) of its original weight. Shorter tau = more reactive; longer tau = more stable.</li>
 *   <li>{@code minimumNumberOfCalls} — minimum samples before the threshold is evaluated.</li>
 * </ul>
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li><strong>{@link #protective()}</strong> — short tau (5s), low minimum (5), threshold 30%;
 *       the rate increases and decays very rapidly.</li>
 *   <li><strong>{@link #balanced()}</strong> — medium tau (30s), medium minimum (10), threshold 50%;
 *       suitable for typical cloud service latencies (default).</li>
 *   <li><strong>{@link #permissive()}</strong> — long tau (120s), high minimum (50), threshold 70%;
 *       provides a very stable, long-term failure average.</li>
 * </ul>
 *
 * <h2>Default Behavior</h2>
 * <p>If {@code build()} is called without configuration, the {@link #balanced()} preset is applied.
 */
public class ContinuousTimeEwmaConfigBuilder extends ExtensionBuilder<ContinuousTimeEwmaConfig> {

    private Double failureRatePercent;
    private Duration timeConstant;
    private Integer minimumNumberOfCalls;

    /**
     * Sets the EWMA time constant (tau). Shorter durations make the average more
     * reactive; longer durations make it more stable.
     *
     * @param tau the time constant as a {@link Duration}
     * @return this builder for chaining
     */
    public ContinuousTimeEwmaConfigBuilder timeConstant(Duration tau) {
        this.timeConstant = tau;
        return this;
    }

    /**
     * Sets the failure rate percentage threshold (1–100).
     *
     * @param failureRatePercent the threshold percentage
     * @return this builder for chaining
     */
    public ContinuousTimeEwmaConfigBuilder failureRatePercent(double failureRatePercent) {
        this.failureRatePercent = failureRatePercent;
        return this;
    }

    /**
     * Sets the minimum number of recorded outcomes before the threshold is evaluated.
     *
     * @param calls the sample-size guard (> 0)
     * @return this builder for chaining
     */
    public ContinuousTimeEwmaConfigBuilder minimumNumberOfCalls(int calls) {
        this.minimumNumberOfCalls = calls;
        return this;
    }

    /**
     * <strong>Protective preset</strong> — short time constant makes the rate decay or
     * increase very rapidly; catches degradation within seconds. Best for critical,
     * low-latency dependencies.
     *
     * <table>
     *   <tr><th>Parameter</th><th>Value</th></tr>
     *   <tr><td>failureRatePercent</td><td>30.0</td></tr>
     *   <tr><td>timeConstant (tau)</td><td>5 seconds</td></tr>
     *   <tr><td>minimumNumberOfCalls</td><td>5</td></tr>
     * </table>
     *
     * @return this builder for chaining
     */
    public ContinuousTimeEwmaConfigBuilder protective() {
        this.failureRatePercent = 30.0;
        this.timeConstant = Duration.ofSeconds(5);
        this.minimumNumberOfCalls = 5;
        return this;
    }

    /**
     * <strong>Balanced preset</strong> — medium time constant suitable for typical cloud
     * service latencies and traffic patterns.
     *
     * <table>
     *   <tr><th>Parameter</th><th>Value</th></tr>
     *   <tr><td>failureRatePercent</td><td>50.0</td></tr>
     *   <tr><td>timeConstant (tau)</td><td>30 seconds</td></tr>
     *   <tr><td>minimumNumberOfCalls</td><td>10</td></tr>
     * </table>
     *
     * @return this builder for chaining
     */
    public ContinuousTimeEwmaConfigBuilder balanced() {
        this.failureRatePercent = 50.0;
        this.timeConstant = Duration.ofSeconds(30);
        this.minimumNumberOfCalls = 10;
        return this;
    }

    /**
     * <strong>Permissive preset</strong> — long time constant provides a very stable,
     * long-term failure average that resists short-term noise. Best for non-critical
     * services or dependencies with known transient instability.
     *
     * <table>
     *   <tr><th>Parameter</th><th>Value</th></tr>
     *   <tr><td>failureRatePercent</td><td>70.0</td></tr>
     *   <tr><td>timeConstant (tau)</td><td>120 seconds</td></tr>
     *   <tr><td>minimumNumberOfCalls</td><td>50</td></tr>
     * </table>
     *
     * @return this builder for chaining
     */
    public ContinuousTimeEwmaConfigBuilder permissive() {
        this.failureRatePercent = 70.0;
        this.timeConstant = Duration.ofSeconds(120);
        this.minimumNumberOfCalls = 50;
        return this;
    }

    /**
     * Builds the configuration. Falls back to {@link #balanced()} if any field is unset.
     *
     * @return a fully initialized {@link ContinuousTimeEwmaConfig}
     */
    @Override
    public ContinuousTimeEwmaConfig build() {
        if (timeConstant == null || minimumNumberOfCalls == null || failureRatePercent == null) {
            balanced();
        }
        return new ContinuousTimeEwmaConfig(failureRatePercent, timeConstant, minimumNumberOfCalls);
    }
}
