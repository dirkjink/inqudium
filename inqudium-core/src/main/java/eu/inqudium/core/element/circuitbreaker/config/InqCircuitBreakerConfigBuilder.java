package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;
import eu.inqudium.core.config.ExtensionBuilder;
import eu.inqudium.core.config.GeneralConfig;
import eu.inqudium.core.config.InqElementCommonConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.element.config.FailurePredicateConfigBuilder;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * Abstract base builder for all circuit breaker configuration variants.
 *
 * <h2>Purpose</h2>
 * <p>Provides the common fluent API for configuring the parameters shared by every
 * circuit breaker — regardless of which {@link FailureMetrics} strategy is used.
 * Concrete subclasses (e.g., one per metrics algorithm) extend this builder, add
 * their algorithm-specific fields, and implement {@link #build()} to produce the
 * final typed configuration.
 *
 * <h2>Type Parameters</h2>
 * <ul>
 *   <li>{@code B} — the concrete builder subclass (self-type for fluent chaining).</li>
 *   <li>{@code E} — the {@link ConfigExtension} type produced by the concrete builder.</li>
 * </ul>
 *
 * <h2>Common Parameters</h2>
 * <table>
 *   <tr><th>Parameter</th><th>Description</th></tr>
 *   <tr><td>{@code name}</td><td>Identifies this breaker in logs, metrics, and events (required).</td></tr>
 *   <tr><td>{@code waitDurationInOpenState}</td><td>How long to stay OPEN before probing via HALF_OPEN.</td></tr>
 *   <tr><td>{@code waitDurationNanos}</td><td>Same wait duration in raw nanoseconds (alternative setter).</td></tr>
 *   <tr><td>{@code successThresholdInHalfOpen}</td><td>Successes needed in HALF_OPEN to close.</td></tr>
 *   <tr><td>{@code permittedCallsInHalfOpen}</td><td>Max trial calls allowed in HALF_OPEN.</td></tr>
 *   <tr><td>{@code recordFailurePredicate}</td><td>Classifies which exceptions count as failures.</td></tr>
 *   <tr><td>{@code metricsFactory}</td><td>Factory producing the initial {@link FailureMetrics} instance.</td></tr>
 *   <tr><td>{@code eventPublisher}</td><td>Custom event publisher (optional; auto-derived if not set).</td></tr>
 *   <tr><td>{@code enableExceptionOptimization}</td><td>Whether to optimize exception handling.</td></tr>
 * </table>
 *
 * <h2>Build Lifecycle</h2>
 * <p>The {@link #common()} method assembles the shared {@link InqCircuitBreakerConfig}
 * from all fields set on this builder, applies validation, and returns it. Concrete
 * subclasses typically call {@code common()} inside their own {@code build()} method
 * and wrap the result together with algorithm-specific configuration.
 *
 * @param <B> the self-type of the concrete builder subclass
 * @param <E> the configuration extension type produced by the concrete builder
 */
public abstract class InqCircuitBreakerConfigBuilder
    <B extends InqCircuitBreakerConfigBuilder<B, E>, E extends ConfigExtension<E>>
    extends ExtensionBuilder<E> {

  private GeneralConfig generalConfig;
  private String name;
  private InqEventPublisher eventPublisher;
  private Long waitDurationNanos;
  private Boolean enableExceptionOptimization;
  private Integer successThresholdInHalfOpen;
  private Integer permittedCallsInHalfOpen;
  private Duration waitDurationInOpenState;
  private Predicate<Throwable> recordFailurePredicate;
  private LongFunction<FailureMetrics> metricsFactory;

  protected InqCircuitBreakerConfigBuilder() {
  }

  /**
   * Receives the framework-wide general configuration, typically injected by the
   * configuration infrastructure before {@code build()} is called.
   *
   * @param generalConfig the general configuration
   */
  @Override
  protected void general(GeneralConfig generalConfig) {
    this.generalConfig = generalConfig;
  }

  /**
   * Provides subclasses with read access to the general configuration, which may
   * be needed to derive defaults (e.g., extracting a global time source).
   *
   * @return the general configuration, or {@code null} if not yet injected
   */
  protected GeneralConfig getGeneralConfig() {
    return this.generalConfig;
  }

  /**
   * Returns {@code this} cast to the concrete builder type for fluent chaining.
   * Must be implemented by each concrete subclass.
   */
  protected abstract B self();

  /**
   * Sets the number of consecutive successes required in the HALF_OPEN state to
   * transition the circuit back to CLOSED.
   *
   * @param successThresholdInHalfOpen the success threshold (> 0)
   * @return this builder for chaining
   */
  public InqCircuitBreakerConfigBuilder<B, E> successThresholdInHalfOpen(Integer successThresholdInHalfOpen) {
    this.successThresholdInHalfOpen = successThresholdInHalfOpen;
    return this;
  }

  /**
   * Sets the maximum number of trial calls permitted through the circuit while
   * in the HALF_OPEN state. These calls probe whether the downstream has recovered.
   *
   * @param permittedCallsInHalfOpen the maximum number of probe calls
   * @return this builder for chaining
   */
  public InqCircuitBreakerConfigBuilder<B, E> permittedCallsInHalfOpen(Integer permittedCallsInHalfOpen) {
    this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
    return this;
  }

  /**
   * Sets the wait duration in the OPEN state as raw nanoseconds. This is an
   * alternative to {@link #waitDurationInOpenState(Duration)}.
   *
   * @param waitDurationNanos the wait duration in nanoseconds
   * @return this builder for chaining
   */
  public InqCircuitBreakerConfigBuilder<B, E> waitDurationNanos(Long waitDurationNanos) {
    this.waitDurationNanos = waitDurationNanos;
    return this;
  }

  /**
   * Sets how long the circuit breaker remains in the OPEN state before transitioning
   * to HALF_OPEN to probe downstream health.
   *
   * @param waitDurationInOpenState the wait duration as a {@link Duration}
   * @return this builder for chaining
   */
  public InqCircuitBreakerConfigBuilder<B, E> waitDurationInOpenState(Duration waitDurationInOpenState) {
    this.waitDurationInOpenState = waitDurationInOpenState;
    return this;
  }

  /**
   * Configures the failure classification predicate using a dedicated sub-builder.
   * The provided {@link Consumer} receives a {@link FailurePredicateConfigBuilder}
   * to which exception classes and custom predicates can be added.
   *
   * <p>Example usage:
   * <pre>
   *   builder.withRecordFailurePredicates(fp -> fp
   *       .recordException(IOException.class)
   *       .recordException(TimeoutException.class)
   *   );
   * </pre>
   *
   * @param customizer a consumer that configures the failure predicate builder
   * @return this builder for chaining
   */
  public InqCircuitBreakerConfigBuilder<B, E> withRecordFailurePredicates(Consumer<FailurePredicateConfigBuilder> customizer) {
    FailurePredicateConfigBuilder builderInstance = FailurePredicateConfigBuilder.failurePredicate();
    customizer.accept(builderInstance);
    recordFailurePredicate = builderInstance.build().finalPredicate();
    return this;
  }

  /**
   * Sets the failure classification predicate directly. Exceptions matching this
   * predicate are recorded as failures; all others are treated as successes from
   * the circuit breaker's perspective.
   *
   * @param recordFailurePredicate the predicate classifying failures
   * @return this builder for chaining
   */
  public InqCircuitBreakerConfigBuilder<B, E> recordFailurePredicate(Predicate<Throwable> recordFailurePredicate) {
    this.recordFailurePredicate = recordFailurePredicate;
    return this;
  }

  /**
   * Sets the factory function that produces the initial {@link FailureMetrics} instance.
   * The factory receives the current nanosecond timestamp and returns a fresh metrics
   * instance. It is invoked at circuit breaker creation and on every reset (CLOSED transition).
   *
   * @param metricsFactory the metrics factory function
   * @return this builder for chaining
   */
  public InqCircuitBreakerConfigBuilder<B, E> metricsFactory(LongFunction<FailureMetrics> metricsFactory) {
    this.metricsFactory = metricsFactory;
    return this;
  }

  /**
   * Sets the name of this circuit breaker element, used for identification in
   * logging, metrics, and event publishing.
   *
   * @param name the circuit breaker name; must not be null or blank
   * @return this builder for chaining
   * @throws NullPointerException     if {@code name} is null
   * @throws IllegalArgumentException if {@code name} is blank
   */
  public B name(String name) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    this.name = name;
    return self();
  }

  /**
   * Enables or disables exception handling optimization for this circuit breaker.
   *
   * @param enableExceptionOptimization {@code true} to enable, {@code false} to disable
   * @return this builder for chaining
   * @throws NullPointerException if the argument is null
   */
  public InqCircuitBreakerConfigBuilder<B, E> enableExceptionOptimization(Boolean enableExceptionOptimization) {
    Objects.requireNonNull(enableExceptionOptimization, "enableExceptionOptimization must not be null");
    this.enableExceptionOptimization = enableExceptionOptimization;
    return this;
  }

  /**
   * Sets a custom event publisher for this circuit breaker. If not set, a default
   * publisher is derived from the element name and type during configuration inference.
   *
   * @param eventPublisher the event publisher, or {@code null} for automatic derivation
   * @return this builder for chaining
   */
  public B eventPublisher(InqEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
    return self();
  }

  /**
   * Assembles the common {@link InqCircuitBreakerConfig} from all fields currently
   * set on this builder. Called by concrete subclasses inside their {@code build()}
   * method.
   *
   * <p>This method also runs cross-field validation via {@link #validate(InqCircuitBreakerConfig)}.
   *
   * @return a fully assembled and validated {@link InqCircuitBreakerConfig}
   * @throws IllegalStateException if required fields (e.g., name) are missing
   */
  protected InqCircuitBreakerConfig common() {

    InqElementCommonConfig common =
        new InqElementCommonConfig(name,
            InqElementType.BULKHEAD,
            eventPublisher,
            enableExceptionOptimization);

    InqCircuitBreakerConfig config = new InqCircuitBreakerConfig(
        generalConfig,
        common,
        waitDurationNanos,
        successThresholdInHalfOpen,
        permittedCallsInHalfOpen,
        waitDurationInOpenState,
        recordFailurePredicate,
        metricsFactory);

    validate(config);
    return config;
  }

  /**
   * Validates cross-field constraints on the fully assembled configuration.
   * Individual field constraints are already enforced by each setter. Currently
   * verifies that a name has been provided — it is required and has no sensible default.
   *
   * @param config the assembled configuration to validate
   * @throws IllegalStateException if the name is missing
   */
  private void validate(InqCircuitBreakerConfig config) {
    if (config.common() == null || config.common().name() == null) {
      throw new IllegalStateException(
          "name must be set. Call name(...) before building the circuit breaker.");
    }
  }
}
