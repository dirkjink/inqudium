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
  public AimdLimitAlgorithmConfigBuilder performant() {
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

  public AimdLimitAlgorithmConfigBuilder initialLimit(int initialLimit) {
    if (initialLimit <= 0) {
      throw new IllegalArgumentException(
          "initialLimit must be positive, got: " + initialLimit);
    }
    this.initialLimit = initialLimit;
    this.customized = true;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder minLimit(int minLimit) {
    if (minLimit <= 0) {
      throw new IllegalArgumentException(
          "minLimit must be positive, got: " + minLimit);
    }
    this.minLimit = minLimit;
    this.customized = true;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder maxLimit(int maxLimit) {
    if (maxLimit <= 0) {
      throw new IllegalArgumentException(
          "maxLimit must be positive, got: " + maxLimit);
    }
    this.maxLimit = maxLimit;
    this.customized = true;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder backoffRatio(double backoffRatio) {
    if (backoffRatio <= 0.0 || backoffRatio >= 1.0) {
      throw new IllegalArgumentException(
          "backoffRatio must be in range (0.0, 1.0), got: " + backoffRatio);
    }
    this.backoffRatio = backoffRatio;
    this.customized = true;
    return this;
  }

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

  public AimdLimitAlgorithmConfigBuilder errorRateThreshold(double errorRateThreshold) {
    if (errorRateThreshold <= 0.0 || errorRateThreshold >= 1.0) {
      throw new IllegalArgumentException(
          "errorRateThreshold must be in range (0.0, 1.0), got: " + errorRateThreshold);
    }
    this.errorRateThreshold = errorRateThreshold;
    this.customized = true;
    return this;
  }

  public AimdLimitAlgorithmConfigBuilder windowedIncrease(boolean windowedIncrease) {
    this.windowedIncrease = windowedIncrease;
    this.customized = true;
    return this;
  }

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
