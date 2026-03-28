package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Central timeout derivation tool (ADR-012).
 *
 * <p>Takes HTTP client timeout components as input — keyed by the agnostic
 * {@link AgnosticTimeoutType} — and computes the TimeLimiter timeout and
 * Circuit Breaker {@code slowCallDurationThreshold} using either RSS
 * (Root Sum of Squares) or worst-case addition.
 *
 * <p>Setting the same {@link AgnosticTimeoutType} more than once always
 * replaces the previous value; no duplicate accumulation occurs.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var profile = InqTimeoutProfile.builder()
 *     .connectTimeout(Duration.ofMillis(250))      // → CONNECTION_ESTABLISHMENT
 *     .responseTimeout(Duration.ofSeconds(3))       // → READ_INACTIVITY
 *     .method(TimeoutCalculation.RSS)
 *     .safetyMarginFactor(1.2)
 *     .build();
 *
 * Duration tlTimeout     = profile.timeLimiterTimeout();
 * Duration slowThreshold = profile.slowCallDurationThreshold();
 * }</pre>
 *
 * <p>The profile is a pure computation — no framework coupling.
 *
 * @since 0.1.0
 */
public final class InqTimeoutProfile {

  // -------------------------------------------------------------------------
  // Fields
  // -------------------------------------------------------------------------

  /** Immutable snapshot of the configured timeout components at build time. */
  private final Map<AgnosticTimeoutType, Duration> timeoutComponents;

  private final TimeoutCalculation method;
  private final double safetyMarginFactor;

  /** Stateless; safe to share. */
  private final TimeoutCalculator calculator;

  // -------------------------------------------------------------------------
  // Construction
  // -------------------------------------------------------------------------

  private InqTimeoutProfile(Builder b) {
    // Defensive copy — the builder's EnumMap is mutable
    this.timeoutComponents = Map.copyOf(b.timeoutComponents);
    this.method = b.method;
    this.safetyMarginFactor = b.safetyMarginFactor;
    this.calculator = new TimeoutCalculator();
  }

  /** Creates a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  // -------------------------------------------------------------------------
  // Core computations
  // -------------------------------------------------------------------------

  /**
   * Computes the recommended TimeLimiter timeout.
   *
   * <p>Delegates to {@link TimeoutCalculator} using the configured components,
   * combination method, and safety margin factor.
   *
   * @return the computed TimeLimiter timeout
   */
  public Duration timeLimiterTimeout() {
    return calculator.calculate(timeoutComponents.values(), method, safetyMarginFactor);
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

  // -------------------------------------------------------------------------
  // Typed accessors — convenience aliases for common timeout types
  // -------------------------------------------------------------------------

  /**
   * Returns the configured {@link AgnosticTimeoutType#CONNECTION_ESTABLISHMENT} timeout.
   *
   * @return the value, or {@link Duration#ZERO} if not configured
   */
  public Duration connectTimeout() {
    return getTimeout(AgnosticTimeoutType.CONNECTION_ESTABLISHMENT);
  }

  /**
   * Returns the configured {@link AgnosticTimeoutType#READ_INACTIVITY} timeout.
   *
   * @return the value, or {@link Duration#ZERO} if not configured
   */
  public Duration responseTimeout() {
    return getTimeout(AgnosticTimeoutType.READ_INACTIVITY);
  }

  /**
   * Returns the configured {@link AgnosticTimeoutType#CONNECTION_ACQUIRE} timeout.
   *
   * @return the value, or {@link Duration#ZERO} if not configured
   */
  public Duration connectionAcquireTimeout() {
    return getTimeout(AgnosticTimeoutType.CONNECTION_ACQUIRE);
  }

  /**
   * Returns the configured {@link AgnosticTimeoutType#WRITE_OPERATION} timeout.
   *
   * @return the value, or {@link Duration#ZERO} if not configured
   */
  public Duration writeOperationTimeout() {
    return getTimeout(AgnosticTimeoutType.WRITE_OPERATION);
  }

  /**
   * Returns the configured {@link AgnosticTimeoutType#SERVER_RESPONSE} timeout
   * (Time To First Byte — TTFB).
   *
   * @return the value, or {@link Duration#ZERO} if not configured
   */
  public Duration serverResponseTimeout() {
    return getTimeout(AgnosticTimeoutType.SERVER_RESPONSE);
  }

  /**
   * Returns the configured timeout for the given {@link AgnosticTimeoutType}.
   *
   * @param type the timeout type; must not be {@code null}
   * @return the configured value, or {@link Duration#ZERO} if not set
   */
  public Duration getTimeout(AgnosticTimeoutType type) {
    return timeoutComponents.getOrDefault(Objects.requireNonNull(type), Duration.ZERO);
  }

  /**
   * Returns a read-only view of all configured timeout components.
   *
   * @return unmodifiable map of all configured timeout types and their durations
   */
  public Map<AgnosticTimeoutType, Duration> getTimeoutComponents() {
    return timeoutComponents;
  }

  // -------------------------------------------------------------------------
  // Strategy / configuration accessors
  // -------------------------------------------------------------------------

  /**
   * Returns the calculation method used.
   *
   * @return {@link TimeoutCalculation#RSS} or {@link TimeoutCalculation#WORST_CASE}
   */
  public TimeoutCalculation getMethod() {
    return method;
  }

  /**
   * Returns the safety margin factor.
   *
   * @return the factor (e.g. {@code 1.2} for a 20 % margin above the computed value)
   */
  public double getSafetyMarginFactor() {
    return safetyMarginFactor;
  }

  // -------------------------------------------------------------------------
  // Builder
  // -------------------------------------------------------------------------

  /**
   * Fluent builder for {@link InqTimeoutProfile}.
   *
   * <p>Each timeout type maps to exactly one {@link AgnosticTimeoutType} key.
   * Calling the same setter twice replaces the previously stored value —
   * no accumulation takes place.
   */
  public static final class Builder {

    /** EnumMap guarantees ordering by declaration and O(1) put/get. */
    private final EnumMap<AgnosticTimeoutType, Duration> timeoutComponents =
        new EnumMap<>(AgnosticTimeoutType.class);

    private TimeoutCalculation method = TimeoutCalculation.RSS;
    private double safetyMarginFactor = 1.2;

    private Builder() {
    }

    // ------------------------------------------------------------------
    // Convenience setters — named after well-known HTTP client parameters
    // ------------------------------------------------------------------

    /**
     * Sets the {@link AgnosticTimeoutType#CONNECTION_ESTABLISHMENT} timeout
     * (TCP + TLS handshake).
     *
     * <p>Replaces any previously set value for this type.
     *
     * @param timeout the connect timeout; must not be {@code null}
     * @return this builder
     */
    public Builder connectTimeout(Duration timeout) {
      return timeout(AgnosticTimeoutType.CONNECTION_ESTABLISHMENT, timeout);
    }

    /**
     * Sets the {@link AgnosticTimeoutType#READ_INACTIVITY} timeout
     * (maximum gap between received data packets).
     *
     * <p>Replaces any previously set value for this type.
     *
     * @param timeout the response / read-inactivity timeout; must not be {@code null}
     * @return this builder
     */
    public Builder responseTimeout(Duration timeout) {
      return timeout(AgnosticTimeoutType.READ_INACTIVITY, timeout);
    }

    /**
     * Sets the {@link AgnosticTimeoutType#CONNECTION_ACQUIRE} timeout
     * (max wait for a free connection from the pool).
     *
     * <p>Replaces any previously set value for this type.
     *
     * @param timeout the pool-acquire timeout; must not be {@code null}
     * @return this builder
     */
    public Builder connectionAcquireTimeout(Duration timeout) {
      return timeout(AgnosticTimeoutType.CONNECTION_ACQUIRE, timeout);
    }

    /**
     * Sets the {@link AgnosticTimeoutType#WRITE_OPERATION} timeout
     * (max time a single socket write is allowed to block).
     *
     * <p>Replaces any previously set value for this type.
     *
     * @param timeout the write-operation timeout; must not be {@code null}
     * @return this builder
     */
    public Builder writeOperationTimeout(Duration timeout) {
      return timeout(AgnosticTimeoutType.WRITE_OPERATION, timeout);
    }

    /**
     * Sets the {@link AgnosticTimeoutType#SERVER_RESPONSE} timeout
     * (maximum wait for the first response byte, TTFB).
     *
     * <p>Replaces any previously set value for this type.
     *
     * @param timeout the server-response (TTFB) timeout; must not be {@code null}
     * @return this builder
     */
    public Builder serverResponseTimeout(Duration timeout) {
      return timeout(AgnosticTimeoutType.SERVER_RESPONSE, timeout);
    }

    /**
     * Generic setter — sets a timeout for the given {@link AgnosticTimeoutType}.
     *
     * <p>Replaces any previously set value for this type.
     * Prefer the named convenience methods ({@link #connectTimeout},
     * {@link #responseTimeout}, etc.) for readability.
     *
     * @param type    the timeout type; must not be {@code null}
     * @param timeout the duration; must not be {@code null}
     * @return this builder
     */
    public Builder timeout(AgnosticTimeoutType type, Duration timeout) {
      timeoutComponents.put(
          Objects.requireNonNull(type, "type must not be null"),
          Objects.requireNonNull(timeout, "timeout must not be null"));
      return this;
    }

    // ------------------------------------------------------------------
    // Strategy setters
    // ------------------------------------------------------------------

    /**
     * Sets the calculation method.
     *
     * @param method {@link TimeoutCalculation#RSS} (default) or
     *               {@link TimeoutCalculation#WORST_CASE}; must not be {@code null}
     * @return this builder
     */
    public Builder method(TimeoutCalculation method) {
      this.method = Objects.requireNonNull(method);
      return this;
    }

    /**
     * Sets the safety margin factor applied to the computed timeout.
     *
     * @param factor the factor (e.g. {@code 1.2} for 20 % margin); must be ≥ 1.0
     * @return this builder
     * @throws IllegalArgumentException if {@code factor} is less than 1.0
     */
    public Builder safetyMarginFactor(double factor) {
      if (factor < 1.0) {
        throw new IllegalArgumentException(
            "Safety margin factor must be >= 1.0, got: " + factor);
      }
      this.safetyMarginFactor = factor;
      return this;
    }

    /**
     * Builds the timeout profile.
     *
     * @return the computed, immutable profile
     */
    public InqTimeoutProfile build() {
      return new InqTimeoutProfile(this);
    }
  }
}
