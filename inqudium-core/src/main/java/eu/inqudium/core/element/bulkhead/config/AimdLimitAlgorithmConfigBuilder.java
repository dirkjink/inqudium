package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.time.Duration;
import java.util.Objects;

public class AimdLimitAlgorithmConfigBuilder extends ExtensionBuilder<AimdLimitAlgorithmConfig> {
    private int initialLimit;
    private int minLimit;
    private int maxLimit;
    private double backoffRatio;
    private Duration smoothingTimeConstant;
    private double errorRateThreshold;
    private boolean windowedIncrease;
    private double minUtilizationThreshold;

    // Tracks whether a preset has been applied as a baseline
    private boolean presetApplied = false;

    // Tracks whether individual setters have been called (after or without a preset)
    private boolean customized = false;

    AimdLimitAlgorithmConfigBuilder() {
    }

    public static AimdLimitAlgorithmConfigBuilder aimdLimitAlgorithm() {
        return new AimdLimitAlgorithmConfigBuilder().balanced();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Preset Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>Protective</b> preset — prioritizes stability over throughput.
     *
     * <p>Designed for critical downstream services where oversaturation is more
     * dangerous than under-utilization (e.g., payment gateways, auth services,
     * databases with strict connection limits).
     *
     * <h3>Characteristics</h3>
     * <ul>
     *   <li><b>Slow growth:</b> Windowed increase ({@code +1/currentLimit}) ensures the
     *       limit climbs by at most +1 per full congestion window, regardless of RPS.</li>
     *   <li><b>Aggressive backoff:</b> Halves the limit on sustained failures ({@code 0.5}).</li>
     *   <li><b>Tolerant error detection:</b> 15% smoothed error rate with a 5-second EWMA
     *       window absorbs transient failure bursts without unnecessary capacity drops.</li>
     *   <li><b>Low utilization gate:</b> Requires only 50% utilization to allow growth,
     *       so the limit can still adapt during moderate-load periods.</li>
     * </ul>
     *
     * @return this builder, configured with protective defaults
     * @throws IllegalStateException if individual setters have already been called
     */
    public AimdLimitAlgorithmConfigBuilder protective() {
        guardPreset();
        this.initialLimit = 20;
        this.minLimit = 1;
        this.maxLimit = 200;
        this.backoffRatio = 0.5;
        this.smoothingTimeConstant = Duration.ofSeconds(5);
        this.errorRateThreshold = 0.15;
        this.windowedIncrease = true;
        this.minUtilizationThreshold = 0.5;
        this.presetApplied = true;
        return this;
    }

    /**
     * <b>Balanced</b> preset — the recommended production default.
     *
     * <p>Suitable for most backend-to-backend communication where the downstream
     * service has moderate and somewhat predictable capacity (e.g., internal
     * microservices, managed databases, message brokers).
     *
     * @return this builder, configured with balanced defaults
     * @throws IllegalStateException if individual setters have already been called
     */
    public AimdLimitAlgorithmConfigBuilder balanced() {
        guardPreset();
        this.initialLimit = 50;
        this.minLimit = 5;
        this.maxLimit = 500;
        this.backoffRatio = 0.7;
        this.smoothingTimeConstant = Duration.ofSeconds(2);
        this.errorRateThreshold = 0.1;
        this.windowedIncrease = true;
        this.minUtilizationThreshold = 0.6;
        this.presetApplied = true;
        return this;
    }

    /**
     * <b>Performant</b> preset — prioritizes throughput over caution.
     *
     * <p>Designed for downstream services with high, elastic capacity where
     * under-utilization is more costly than brief oversaturation (e.g., autoscaling
     * compute clusters, CDN origins, horizontally scaled stateless services).
     *
     * @return this builder, configured with performant defaults
     * @throws IllegalStateException if individual setters have already been called
     */
    public AimdLimitAlgorithmConfigBuilder permissive() {
        guardPreset();
        this.initialLimit = 100;
        this.minLimit = 10;
        this.maxLimit = 1000;
        this.backoffRatio = 0.85;
        this.smoothingTimeConstant = Duration.ofSeconds(1);
        this.errorRateThreshold = 0.05;
        this.windowedIncrease = false;
        this.minUtilizationThreshold = 0.75;
        this.presetApplied = true;
        return this;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Individual Setters — each guards its own value immediately
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sets the starting concurrency limit before any adjustments are made.
     *
     * @param initialLimit the initial concurrency limit, must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code initialLimit} is not positive
     */
    public AimdLimitAlgorithmConfigBuilder initialLimit(int initialLimit) {
        if (initialLimit <= 0) {
            throw new IllegalArgumentException(
                    "initialLimit must be positive, got: " + initialLimit);
        }
        this.initialLimit = initialLimit;
        this.customized = true;
        return this;
    }

    /**
     * Sets the lower bound for the concurrency limit. The algorithm will never
     * reduce the limit below this value, even under sustained failures.
     *
     * @param minLimit the minimum concurrency limit, must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code minLimit} is not positive
     */
    public AimdLimitAlgorithmConfigBuilder minLimit(int minLimit) {
        if (minLimit <= 0) {
            throw new IllegalArgumentException(
                    "minLimit must be positive, got: " + minLimit);
        }
        this.minLimit = minLimit;
        this.customized = true;
        return this;
    }

    /**
     * Sets the upper bound for the concurrency limit. The algorithm will never
     * increase the limit above this value, regardless of observed throughput.
     *
     * @param maxLimit the maximum concurrency limit, must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code maxLimit} is not positive
     */
    public AimdLimitAlgorithmConfigBuilder maxLimit(int maxLimit) {
        if (maxLimit <= 0) {
            throw new IllegalArgumentException(
                    "maxLimit must be positive, got: " + maxLimit);
        }
        this.maxLimit = maxLimit;
        this.customized = true;
        return this;
    }

    /**
     * Sets the multiplicative decrease factor applied to the limit when sustained
     * failures are detected. A value of 0.5 halves the limit; 0.9 retains 90%.
     *
     * @param backoffRatio the backoff ratio, must be in the open interval (0.0, 1.0)
     * @return this builder
     * @throws IllegalArgumentException if {@code backoffRatio} is not in (0.0, 1.0)
     */
    public AimdLimitAlgorithmConfigBuilder backoffRatio(double backoffRatio) {
        if (backoffRatio <= 0.0 || backoffRatio >= 1.0) {
            throw new IllegalArgumentException(
                    "backoffRatio must be in range (0.0, 1.0), got: " + backoffRatio);
        }
        this.backoffRatio = backoffRatio;
        this.customized = true;
        return this;
    }

    /**
     * Sets the EWMA time constant for smoothing the observed error rate.
     * Larger values produce heavier smoothing, filtering out transient spikes.
     *
     * @param smoothingTimeConstant the smoothing time constant, must be positive and non-null
     * @return this builder
     * @throws NullPointerException     if {@code smoothingTimeConstant} is null
     * @throws IllegalArgumentException if {@code smoothingTimeConstant} is zero or negative
     */
    public AimdLimitAlgorithmConfigBuilder smoothingTimeConstant(Duration smoothingTimeConstant) {
        Objects.requireNonNull(smoothingTimeConstant,
                "smoothingTimeConstant must not be null");
        if (smoothingTimeConstant.isNegative() || smoothingTimeConstant.isZero()) {
            throw new IllegalArgumentException(
                    "smoothingTimeConstant must be positive, got: " + smoothingTimeConstant);
        }
        this.smoothingTimeConstant = smoothingTimeConstant;
        this.customized = true;
        return this;
    }

    /**
     * Sets the smoothed error rate above which the algorithm triggers a
     * multiplicative decrease of the concurrency limit.
     *
     * @param errorRateThreshold the error rate threshold, must be in the open interval (0.0, 1.0)
     * @return this builder
     * @throws IllegalArgumentException if {@code errorRateThreshold} is not in (0.0, 1.0)
     */
    public AimdLimitAlgorithmConfigBuilder errorRateThreshold(double errorRateThreshold) {
        if (errorRateThreshold <= 0.0 || errorRateThreshold >= 1.0) {
            throw new IllegalArgumentException(
                    "errorRateThreshold must be in range (0.0, 1.0), got: " + errorRateThreshold);
        }
        this.errorRateThreshold = errorRateThreshold;
        this.customized = true;
        return this;
    }

    /**
     * Controls whether the additive increase is windowed ({@code +1} per congestion
     * window, independent of RPS) or fixed ({@code +1} per successful request).
     * Windowed increase produces slower, more predictable growth.
     *
     * @param windowedIncrease {@code true} for windowed increase, {@code false} for per-request
     * @return this builder
     */
    public AimdLimitAlgorithmConfigBuilder windowedIncrease(boolean windowedIncrease) {
        this.windowedIncrease = windowedIncrease;
        this.customized = true;
        return this;
    }

    /**
     * Sets the minimum utilization ratio required before the algorithm is allowed
     * to increase the concurrency limit. Prevents limit inflation during idle periods.
     *
     * @param minUtilizationThreshold the utilization threshold, must be in the closed interval [0.0, 1.0]
     * @return this builder
     * @throws IllegalArgumentException if {@code minUtilizationThreshold} is not in [0.0, 1.0]
     */
    public AimdLimitAlgorithmConfigBuilder minUtilizationThreshold(double minUtilizationThreshold) {
        if (minUtilizationThreshold < 0.0 || minUtilizationThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "minUtilizationThreshold must be in range [0.0, 1.0], got: "
                            + minUtilizationThreshold);
        }
        this.minUtilizationThreshold = minUtilizationThreshold;
        this.customized = true;
        return this;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Build
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public AimdLimitAlgorithmConfig build() {
        AimdLimitAlgorithmConfig config = new AimdLimitAlgorithmConfig(
                initialLimit,
                minLimit,
                maxLimit,
                backoffRatio,
                smoothingTimeConstant,
                errorRateThreshold,
                windowedIncrease,
                minUtilizationThreshold
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
                            + "Example: aimdLimitAlgorithm().protective().initialLimit(30)");
        }
    }

    /**
     * Validates cross-field constraints on the fully inferred configuration record.
     * Individual field constraints are already enforced by each setter.
     */
    private void validate(AimdLimitAlgorithmConfig config) {
        Objects.requireNonNull(config.smoothingTimeConstant(),
                "smoothingTimeConstant has not been set. Either apply a preset "
                        + "(e.g. balanced()) as a baseline, or set all values explicitly.");

        if (config.initialLimit() <= 0 || config.minLimit() <= 0 || config.maxLimit() <= 0) {
            throw new IllegalStateException(
                    "Limit values (initialLimit, minLimit, maxLimit) have not been fully set. "
                            + "Either apply a preset (e.g. balanced()) as a baseline, "
                            + "or set all values explicitly.");
        }

        if (config.backoffRatio() <= 0.0 || config.backoffRatio() >= 1.0) {
            throw new IllegalStateException(
                    "backoffRatio has not been set to a valid value. Either apply a preset "
                            + "(e.g. balanced()) as a baseline, or set all values explicitly.");
        }

        if (config.errorRateThreshold() <= 0.0 || config.errorRateThreshold() >= 1.0) {
            throw new IllegalStateException(
                    "errorRateThreshold has not been set to a valid value. Either apply a preset "
                            + "(e.g. balanced()) as a baseline, or set all values explicitly.");
        }

        if (config.minLimit() > config.maxLimit()) {
            throw new IllegalArgumentException(
                    "minLimit (" + config.minLimit() + ") must not exceed maxLimit ("
                            + config.maxLimit() + ")");
        }

        if (config.initialLimit() < config.minLimit()
                || config.initialLimit() > config.maxLimit()) {
            throw new IllegalArgumentException(
                    "initialLimit (" + config.initialLimit() + ") must be between minLimit ("
                            + config.minLimit() + ") and maxLimit (" + config.maxLimit() + ")");
        }
    }
}
