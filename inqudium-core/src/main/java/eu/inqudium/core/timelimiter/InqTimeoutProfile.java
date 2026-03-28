package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Central timeout derivation tool (ADR-012).
 *
 * <p>Takes HTTP client timeout components as input and computes the TimeLimiter
 * timeout and Circuit Breaker {@code slowCallDurationThreshold} using either
 * RSS (Root Sum of Squares) or worst-case addition.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var profile = InqTimeoutProfile.builder()
 *     .connectTimeout(Duration.ofMillis(250))
 *     .responseTimeout(Duration.ofSeconds(3))
 *     .method(TimeoutCalculation.RSS)
 *     .safetyMarginFactor(1.2)   // 20% above computed value
 *     .build();
 *
 * Duration tlTimeout = profile.timeLimiterTimeout();
 * Duration slowThreshold = profile.slowCallDurationThreshold();
 * }</pre>
 *
 * <p>The profile is a pure computation — no framework coupling. Works with
 * Netty, OkHttp, Apache HttpClient, or any other client.
 *
 * @since 0.1.0
 */
public final class InqTimeoutProfile {

  private final List<Duration> timeoutComponents;
  private final TimeoutCalculation method;
  private final double safetyMarginFactor;

  private InqTimeoutProfile(Builder b) {
    this.timeoutComponents = List.copyOf(b.timeoutComponents);
    this.method = b.method;
    this.safetyMarginFactor = b.safetyMarginFactor;
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Computes the recommended TimeLimiter timeout.
   *
   * <p>Uses the selected method (RSS or worst-case) to combine timeout
   * components, then applies the safety margin factor.
   *
   * @return the computed TimeLimiter timeout
   */
  public Duration timeLimiterTimeout() {
    if (timeoutComponents.isEmpty()) {
      return Duration.ofSeconds(5); // fallback
    }

    double nominalMs = 0;
    double toleranceSumOrSquaredSum = 0;

    for (var component : timeoutComponents) {
      double ms = component.toMillis();
      nominalMs += ms * 0.5; // assume nominal is ~50% of timeout
      double tolerance = ms * 0.5; // tolerance is the other 50%

      switch (method) {
        case RSS -> toleranceSumOrSquaredSum += tolerance * tolerance;
        case WORST_CASE -> toleranceSumOrSquaredSum += tolerance;
      }
    }

    double combinedTolerance = switch (method) {
      case RSS -> Math.sqrt(toleranceSumOrSquaredSum);
      case WORST_CASE -> toleranceSumOrSquaredSum;
    };

    double totalMs = (nominalMs + combinedTolerance) * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }

  /**
   * Computes the recommended Circuit Breaker {@code slowCallDurationThreshold}.
   *
   * <p>Aligned with the TimeLimiter timeout — a call is only "slow" if it
   * reaches the safety net's limit (ADR-012).
   *
   * @return the computed slow call threshold (equal to {@link #timeLimiterTimeout()})
   */
  public Duration slowCallDurationThreshold() {
    return timeLimiterTimeout();
  }

  /**
   * Returns the first timeout component (typically connectTimeout).
   *
   * @return the connect timeout, or Duration.ZERO if no components configured
   */
  public Duration connectTimeout() {
    return timeoutComponents.isEmpty() ? Duration.ZERO : timeoutComponents.getFirst();
  }

  /**
   * Returns the second timeout component (typically responseTimeout).
   *
   * @return the response timeout, or Duration.ZERO if fewer than 2 components
   */
  public Duration responseTimeout() {
    return timeoutComponents.size() < 2 ? Duration.ZERO : timeoutComponents.get(1);
  }

  /**
   * Returns the calculation method used.
   *
   * @return RSS or WORST_CASE
   */
  public TimeoutCalculation getMethod() {
    return method;
  }

  /**
   * Returns the safety margin factor.
   *
   * @return the factor (e.g. 1.2 for 20% margin)
   */
  public double getSafetyMarginFactor() {
    return safetyMarginFactor;
  }

  public static final class Builder {

    private final List<Duration> timeoutComponents = new ArrayList<>();
    private TimeoutCalculation method = TimeoutCalculation.RSS;
    private double safetyMarginFactor = 1.2;

    private Builder() {
    }

    /**
     * Adds the HTTP connect timeout as a component.
     *
     * @param timeout the connect timeout
     * @return this builder
     */
    public Builder connectTimeout(Duration timeout) {
      timeoutComponents.add(Objects.requireNonNull(timeout));
      return this;
    }

    /**
     * Adds the HTTP response timeout as a component.
     *
     * @param timeout the response timeout
     * @return this builder
     */
    public Builder responseTimeout(Duration timeout) {
      timeoutComponents.add(Objects.requireNonNull(timeout));
      return this;
    }

    /**
     * Adds an additional timeout component (e.g. TLS handshake).
     *
     * @param timeout the additional timeout
     * @return this builder
     */
    public Builder additionalTimeout(Duration timeout) {
      timeoutComponents.add(Objects.requireNonNull(timeout));
      return this;
    }

    /**
     * Sets the calculation method.
     *
     * @param method RSS (default) or WORST_CASE
     * @return this builder
     */
    public Builder method(TimeoutCalculation method) {
      this.method = Objects.requireNonNull(method);
      return this;
    }

    /**
     * Sets the safety margin factor applied to the computed timeout.
     *
     * @param factor the factor (e.g. 1.2 for 20% margin). Default: 1.2
     * @return this builder
     */
    public Builder safetyMarginFactor(double factor) {
      if (factor < 1.0) throw new IllegalArgumentException("Safety margin factor must be >= 1.0, got: " + factor);
      this.safetyMarginFactor = factor;
      return this;
    }

    /**
     * Builds the timeout profile.
     *
     * @return the computed profile
     */
    public InqTimeoutProfile build() {
      return new InqTimeoutProfile(this);
    }
  }
}
