package eu.inqudium.imperative.bulkhead.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.time.Duration;
import java.util.Objects;

/**
 * @deprecated Orphaned alongside {@link CoDelBulkheadStrategyConfig} by REFACTORING.md
 *             step 1.10; revisit when the strategy hot-swap DSL is settled in phase&nbsp;2
 *             (step&nbsp;2.10).
 */
@Deprecated(forRemoval = true, since = "0.4.0")
@SuppressWarnings("deprecation")
public class CoDelBulkheadStrategyConfigBuilder extends ExtensionBuilder<CoDelBulkheadStrategyConfig> {
    private Duration targetDelay;
    private Duration interval;

    // Tracks whether a preset has been applied as a baseline
    private boolean presetApplied = false;

    // Tracks whether individual setters have been called (after or without a preset)
    private boolean customized = false;

    CoDelBulkheadStrategyConfigBuilder() {
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Preset Factory Methods
    // ──────────────────────────────────────────────────────────────────────────

    public static CoDelBulkheadStrategyConfigBuilder coDelBulkheadStrategy() {
        return new CoDelBulkheadStrategyConfigBuilder().balanced();
    }

    /**
     * <b>Protective</b> preset — prioritizes downstream safety over throughput.
     *
     * <p>Designed for critical downstream services where queue buildup is an early
     * warning of cascading failure (e.g., payment gateways, auth services, databases
     * with strict connection limits).
     *
     * @return this builder, configured with protective defaults
     * @throws IllegalStateException if individual setters have already been called
     */
    public CoDelBulkheadStrategyConfigBuilder protective() {
        guardPreset();
        this.targetDelay = Duration.ofMillis(50);
        this.interval = Duration.ofMillis(500);
        this.presetApplied = true;
        return this;
    }

    /**
     * <b>Balanced</b> preset — the recommended production default.
     *
     * <p>Suitable for most backend-to-backend communication where the downstream
     * service has moderate capacity and occasional latency spikes are normal
     * (e.g., internal microservices, managed databases, message brokers).
     *
     * @return this builder, configured with balanced defaults
     * @throws IllegalStateException if individual setters have already been called
     */
    public CoDelBulkheadStrategyConfigBuilder balanced() {
        guardPreset();
        this.targetDelay = Duration.ofMillis(100);
        this.interval = Duration.ofSeconds(1);
        this.presetApplied = true;
        return this;
    }

    /**
     * <b>Performant</b> preset — prioritizes throughput over caution.
     *
     * <p>Designed for downstream services with high, elastic capacity where
     * brief queue buildup is acceptable and aggressive dropping would waste
     * capacity (e.g., autoscaling compute clusters, CDN origins, horizontally
     * scaled stateless services).
     *
     * @return this builder, configured with performant defaults
     * @throws IllegalStateException if individual setters have already been called
     */
    public CoDelBulkheadStrategyConfigBuilder performant() {
        guardPreset();
        this.targetDelay = Duration.ofMillis(250);
        this.interval = Duration.ofSeconds(2);
        this.presetApplied = true;
        return this;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Individual Setters — each guards its own value immediately
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sets the sojourn time threshold below which a request is considered
     * "on target". Requests waiting longer than this for a permit start the
     * CoDel congestion stopwatch.
     *
     * @param targetDelay the target delay threshold, must be positive and non-null
     * @return this builder
     * @throws NullPointerException     if {@code targetDelay} is null
     * @throws IllegalArgumentException if {@code targetDelay} is zero or negative
     */
    public CoDelBulkheadStrategyConfigBuilder targetDelay(Duration targetDelay) {
        Objects.requireNonNull(targetDelay, "targetDelay must not be null");
        if (targetDelay.isNegative() || targetDelay.isZero()) {
            throw new IllegalArgumentException(
                    "targetDelay must be positive, got: " + targetDelay);
        }
        this.targetDelay = targetDelay;
        this.customized = true;
        return this;
    }

    /**
     * Sets the minimum sustained congestion duration before the first drop occurs.
     * If sojourn times remain above {@code targetDelay} for this entire interval,
     * CoDel begins shedding load.
     *
     * @param interval the congestion interval, must be positive and non-null
     * @return this builder
     * @throws NullPointerException     if {@code interval} is null
     * @throws IllegalArgumentException if {@code interval} is zero or negative
     */
    public CoDelBulkheadStrategyConfigBuilder interval(Duration interval) {
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException(
                    "interval must be positive, got: " + interval);
        }
        this.interval = interval;
        this.customized = true;
        return this;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Build
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public CoDelBulkheadStrategyConfig build() {
        CoDelBulkheadStrategyConfig config = new CoDelBulkheadStrategyConfig(
                targetDelay,
                interval
        ).inference();

        validate(config);
        return config;
    }

    /**
     * Prevents calling a preset after individual setters have been used.
     * Presets must be applied first as a baseline for further customization.
     */
    private void guardPreset() {
        if (customized) {
            throw new IllegalStateException(
                    "Cannot apply a preset after individual setters have been called. "
                            + "Presets must be applied first as a baseline, then customized. "
                            + "Example: coDelBulkheadStrategy().protective().targetDelay(Duration.ofMillis(75))");
        }
    }

    /**
     * Validates cross-field constraints on the fully inferred configuration record.
     * Individual field constraints are already enforced by each setter.
     */
    private void validate(CoDelBulkheadStrategyConfig config) {
        // Detect unconfigured fields
        if (config.targetDelay() == null || config.interval() == null) {
            throw new IllegalStateException(
                    "targetDelay and interval have not been fully set. Either apply a preset "
                            + "(e.g. balanced()) as a baseline, or set all values explicitly.");
        }

        // Cross-field invariant: CoDel requires target < interval
        if (config.targetDelay().compareTo(config.interval()) >= 0) {
            throw new IllegalArgumentException(
                    "targetDelay (" + config.targetDelay() + ") must be shorter than interval ("
                            + config.interval() + "). CoDel requires target < interval to function correctly.");
        }
    }
}
