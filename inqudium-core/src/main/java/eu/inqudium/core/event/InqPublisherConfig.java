package eu.inqudium.core.event;

import java.time.Duration;

/**
 * Configuration for consumer limits and expiry behavior on an {@link InqEventPublisher}.
 *
 * <p>Controls three aspects:
 * <ul>
 *   <li><strong>Soft limit</strong> — when the active consumer count reaches this
 *       threshold, a warning is logged. No consumers are rejected.</li>
 *   <li><strong>Hard limit</strong> — when the active consumer count reaches this
 *       threshold, further registrations are rejected with an
 *       {@link IllegalStateException}. Set to {@link Integer#MAX_VALUE} to disable.</li>
 *   <li><strong>Expiry check interval</strong> — how often the background
 *       {@link InqConsumerExpiryWatchdog} sweeps expired TTL-based consumers.
 *       The watchdog only starts when the first TTL subscription is registered.</li>
 * </ul>
 *
 * <p>Both limits are evaluated against <em>active</em> (non-expired) consumers.
 * Expired TTL-based consumers are swept before any limit check during registration,
 * and periodically by the background watchdog.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Default — soft limit 256, no hard limit, 60s expiry check
 * var config = InqPublisherConfig.defaultConfig();
 *
 * // Custom — warn at 64, reject at 128, sweep every 500ms
 * var config = InqPublisherConfig.of(64, 128, Duration.ofMillis(500));
 *
 * // Only soft limit, no hard rejection, default expiry interval
 * var config = InqPublisherConfig.withSoftLimit(100);
 *
 * var publisher = InqEventPublisher.create("myElement", elementType, config);
 * }</pre>
 *
 * @param softLimit           the consumer count at which a warning is logged (must be &ge; 1)
 * @param hardLimit           the consumer count at which new registrations are rejected
 *                            (must be &ge; softLimit)
 * @param expiryCheckInterval the interval at which expired TTL consumers are swept
 *                            by the background watchdog (must be positive)
 * @since 0.2.0
 */
public record InqPublisherConfig(
    int softLimit,
    int hardLimit,
    Duration expiryCheckInterval,
    boolean traceEnabled
) {

  /**
   * Default soft limit threshold.
   */
  private static final int DEFAULT_SOFT_LIMIT = 256;

  /**
   * Default interval between expiry sweeps.
   */
  private static final Duration DEFAULT_EXPIRY_CHECK_INTERVAL = Duration.ofSeconds(60);

  /**
   * Default configuration — warns at 256 consumers, no hard limit, 60s expiry check.
   */
  private static final InqPublisherConfig DEFAULT =
      new InqPublisherConfig(DEFAULT_SOFT_LIMIT,
          Integer.MAX_VALUE,
          DEFAULT_EXPIRY_CHECK_INTERVAL,
          false);

  /**
   * Validates all parameters on construction.
   */
  public InqPublisherConfig {
    if (softLimit < 1) {
      throw new IllegalArgumentException("softLimit must be >= 1, was: " + softLimit);
    }
    if (hardLimit < softLimit) {
      throw new IllegalArgumentException(
          "hardLimit (" + hardLimit + ") must be >= softLimit (" + softLimit + ")");
    }
    if (expiryCheckInterval == null) {
      throw new IllegalArgumentException("expiryCheckInterval must not be null");
    }
    if (expiryCheckInterval.toMillis() < 100) {
      throw new IllegalArgumentException("expiryCheckInterval must not be less than 100ms, was: " + expiryCheckInterval.toMillis());
    }
    if (expiryCheckInterval.isNegative() || expiryCheckInterval.isZero()) {
      throw new IllegalArgumentException(
          "expiryCheckInterval must be positive, was: " + expiryCheckInterval);
    }
  }

  /**
   * Returns the default configuration: soft limit 256, no hard limit, 60s expiry check.
   *
   * @return the default configuration
   */
  public static InqPublisherConfig defaultConfig() {
    return DEFAULT;
  }

  /**
   * Creates a configuration with soft limit, hard limit, and custom expiry interval.
   *
   * @param softLimit           the consumer count at which a warning is logged
   * @param hardLimit           the consumer count at which new registrations are rejected
   * @param expiryCheckInterval the interval between expiry sweeps
   * @return a new configuration
   */
  public static InqPublisherConfig of(int softLimit,
                                      int hardLimit,
                                      Duration expiryCheckInterval,
                                      boolean traceEnabled) {
    return new InqPublisherConfig(softLimit,
        hardLimit,
        expiryCheckInterval,
        traceEnabled);
  }

  /**
   * Creates a configuration with soft limit, hard limit, and custom expiry interval.
   *
   * @param softLimit           the consumer count at which a warning is logged
   * @param hardLimit           the consumer count at which new registrations are rejected
   * @param expiryCheckInterval the interval between expiry sweeps
   * @return a new configuration
   */
  public static InqPublisherConfig of(int softLimit,
                                      int hardLimit,
                                      Duration expiryCheckInterval) {
    return new InqPublisherConfig(softLimit,
        hardLimit,
        expiryCheckInterval,
        false);
  }

  /**
   * Creates a configuration with soft and hard limits, using the default expiry interval (60s).
   *
   * @param softLimit the consumer count at which a warning is logged
   * @param hardLimit the consumer count at which new registrations are rejected
   * @return a new configuration
   */
  public static InqPublisherConfig of(int softLimit, int hardLimit) {
    return new InqPublisherConfig(softLimit,
        hardLimit,
        DEFAULT_EXPIRY_CHECK_INTERVAL,
        false);
  }

  /**
   * Creates a configuration with only a soft limit (no hard rejection),
   * using the default expiry interval.
   *
   * @param softLimit the consumer count at which a warning is logged
   * @return a new configuration with {@code hardLimit = Integer.MAX_VALUE}
   */
  public static InqPublisherConfig withSoftLimit(int softLimit) {
    return new InqPublisherConfig(softLimit,
        Integer.MAX_VALUE,
        DEFAULT_EXPIRY_CHECK_INTERVAL,
        false);
  }

  /**
   * Creates a configuration with both limits set to the same value,
   * using the default expiry interval.
   *
   * @param limit the consumer count for both soft and hard limit
   * @return a new configuration
   */
  public static InqPublisherConfig withHardLimit(int limit) {
    return new InqPublisherConfig(limit,
        limit,
        DEFAULT_EXPIRY_CHECK_INTERVAL,
        false);
  }

  /**
   * Returns {@code true} if a hard limit is configured (not {@link Integer#MAX_VALUE}).
   */
  public boolean hasHardLimit() {
    return hardLimit != Integer.MAX_VALUE;
  }
}
