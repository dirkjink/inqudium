package eu.inqudium.core.element.bulkhead.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.time.Duration;
import java.util.Objects;

public class VegasLimitAlgorithmConfigBuilder extends ExtensionBuilder<VegasLimitAlgorithmConfig> {
  private int initialLimit;
  private int minLimit;
  private int maxLimit;
  private Duration smoothingTimeConstant;
  private Duration baselineDriftTimeConstant;
  private Duration errorRateSmoothingTimeConstant;
  private double errorRateThreshold;
  private double minUtilizationThreshold;

  // Tracks whether a preset has been applied as a baseline
  private boolean presetApplied = false;

  // Tracks whether individual setters have been called (after or without a preset)
  private boolean customized = false;

  VegasLimitAlgorithmConfigBuilder() {
  }

  public static VegasLimitAlgorithmConfigBuilder vegasLimitAlgorithm() {
    return new VegasLimitAlgorithmConfigBuilder().balanced();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Preset Methods
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * <b>Protective</b> preset — prioritizes stability over throughput.
   *
   * <p>Designed for latency-sensitive downstream services where even brief
   * oversaturation causes cascading failures (e.g., payment gateways, auth
   * services, databases with strict connection limits).
   *
   * @return this builder, configured with protective defaults
   * @throws IllegalStateException if individual setters have already been called
   */
  public VegasLimitAlgorithmConfigBuilder protective() {
    guardPreset();
    this.initialLimit = 20;
    this.minLimit = 1;
    this.maxLimit = 200;
    this.smoothingTimeConstant = Duration.ofSeconds(2);
    this.baselineDriftTimeConstant = Duration.ofSeconds(30);
    this.errorRateSmoothingTimeConstant = Duration.ofSeconds(10);
    this.errorRateThreshold = 0.15;
    this.minUtilizationThreshold = 0.5;
    this.presetApplied = true;
    return this;
  }

  /**
   * <b>Balanced</b> preset — the recommended production default.
   *
   * <p>Suitable for most backend-to-backend communication where the downstream
   * service has moderate, somewhat predictable capacity and latency characteristics
   * (e.g., internal microservices, managed databases, message brokers).
   *
   * @return this builder, configured with balanced defaults
   * @throws IllegalStateException if individual setters have already been called
   */
  public VegasLimitAlgorithmConfigBuilder balanced() {
    guardPreset();
    this.initialLimit = 50;
    this.minLimit = 5;
    this.maxLimit = 500;
    this.smoothingTimeConstant = Duration.ofSeconds(1);
    this.baselineDriftTimeConstant = Duration.ofSeconds(10);
    this.errorRateSmoothingTimeConstant = Duration.ofSeconds(5);
    this.errorRateThreshold = 0.1;
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
  public VegasLimitAlgorithmConfigBuilder performant() {
    guardPreset();
    this.initialLimit = 100;
    this.minLimit = 10;
    this.maxLimit = 1000;
    this.smoothingTimeConstant = Duration.ofMillis(500);
    this.baselineDriftTimeConstant = Duration.ofSeconds(5);
    this.errorRateSmoothingTimeConstant = Duration.ofSeconds(3);
    this.errorRateThreshold = 0.05;
    this.minUtilizationThreshold = 0.75;
    this.presetApplied = true;
    return this;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Individual Setters — each guards its own value immediately
  // ──────────────────────────────────────────────────────────────────────────

  public VegasLimitAlgorithmConfigBuilder initialLimit(int initialLimit) {
    if (initialLimit <= 0) {
      throw new IllegalArgumentException(
          "initialLimit must be positive, got: " + initialLimit);
    }
    this.initialLimit = initialLimit;
    this.customized = true;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder minLimit(int minLimit) {
    if (minLimit <= 0) {
      throw new IllegalArgumentException(
          "minLimit must be positive, got: " + minLimit);
    }
    this.minLimit = minLimit;
    this.customized = true;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder maxLimit(int maxLimit) {
    if (maxLimit <= 0) {
      throw new IllegalArgumentException(
          "maxLimit must be positive, got: " + maxLimit);
    }
    this.maxLimit = maxLimit;
    this.customized = true;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder smoothingTimeConstant(Duration smoothingTimeConstant) {
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

  public VegasLimitAlgorithmConfigBuilder baselineDriftTimeConstant(Duration baselineDriftTimeConstant) {
    Objects.requireNonNull(baselineDriftTimeConstant,
        "baselineDriftTimeConstant must not be null");
    if (baselineDriftTimeConstant.isNegative() || baselineDriftTimeConstant.isZero()) {
      throw new IllegalArgumentException(
          "baselineDriftTimeConstant must be positive, got: " + baselineDriftTimeConstant);
    }
    this.baselineDriftTimeConstant = baselineDriftTimeConstant;
    this.customized = true;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder errorRateSmoothingTimeConstant(Duration errorRateSmoothingTimeConstant) {
    Objects.requireNonNull(errorRateSmoothingTimeConstant,
        "errorRateSmoothingTimeConstant must not be null");
    if (errorRateSmoothingTimeConstant.isNegative() || errorRateSmoothingTimeConstant.isZero()) {
      throw new IllegalArgumentException(
          "errorRateSmoothingTimeConstant must be positive, got: "
              + errorRateSmoothingTimeConstant);
    }
    this.errorRateSmoothingTimeConstant = errorRateSmoothingTimeConstant;
    this.customized = true;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder errorRateThreshold(double errorRateThreshold) {
    if (errorRateThreshold <= 0.0 || errorRateThreshold >= 1.0) {
      throw new IllegalArgumentException(
          "errorRateThreshold must be in range (0.0, 1.0), got: " + errorRateThreshold);
    }
    this.errorRateThreshold = errorRateThreshold;
    this.customized = true;
    return this;
  }

  public VegasLimitAlgorithmConfigBuilder minUtilizationThreshold(double minUtilizationThreshold) {
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
  public VegasLimitAlgorithmConfig build() {
    VegasLimitAlgorithmConfig config = new VegasLimitAlgorithmConfig(
        initialLimit,
        minLimit,
        maxLimit,
        smoothingTimeConstant,
        baselineDriftTimeConstant,
        errorRateSmoothingTimeConstant,
        errorRateThreshold,
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
              + "Example: vegasLimitAlgorithm().protective().initialLimit(30)");
    }
  }

  /**
   * Validates cross-field constraints on the fully inferred configuration record.
   * Individual field constraints are already enforced by each setter.
   */
  private void validate(VegasLimitAlgorithmConfig config) {
    // Detect unconfigured fields — primitive defaults indicate missing setup
    if (config.smoothingTimeConstant() == null
        || config.baselineDriftTimeConstant() == null
        || config.errorRateSmoothingTimeConstant() == null) {
      throw new IllegalStateException(
          "Duration values have not been fully set. Either apply a preset "
              + "(e.g. balanced()) as a baseline, or set all values explicitly.");
    }

    if (config.initialLimit() <= 0 || config.minLimit() <= 0 || config.maxLimit() <= 0) {
      throw new IllegalStateException(
          "Limit values (initialLimit, minLimit, maxLimit) have not been fully set. "
              + "Either apply a preset (e.g. balanced()) as a baseline, "
              + "or set all values explicitly.");
    }

    if (config.errorRateThreshold() <= 0.0 || config.errorRateThreshold() >= 1.0) {
      throw new IllegalStateException(
          "errorRateThreshold has not been set to a valid value. Either apply a preset "
              + "(e.g. balanced()) as a baseline, or set all values explicitly.");
    }

    // Cross-field invariants
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
