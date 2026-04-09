package eu.inqudium.core.element.circuitbreaker.dsl;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfigBuilder;
import eu.inqudium.core.element.circuitbreaker.config.SlidingWindowConfigBuilder;
import eu.inqudium.core.element.circuitbreaker.config.TimeBasedSlidingWindowConfigBuilder;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static eu.inqudium.core.element.config.FailurePredicateConfigBuilder.failurePredicate;

class DefaultCircuitBreakerProtection implements CircuitBreakerNaming, CircuitBreakerProtection {

  private final InqCircuitBreakerConfigBuilder<?, ?> inqBuilder;
  private String name;
  private int failureThreshold = 50;
  private Duration waitDurationInOpenState = Duration.ofSeconds(60);
  private int permittedNumberOfCallsInHalfOpenState = 10;
  private FailureMetricsConfig metricsConfig;
  private List<Class<? extends Throwable>> recordedExceptions = List.of(Exception.class);
  private List<Class<? extends Throwable>> ignoredExceptions = List.of();

  public DefaultCircuitBreakerProtection(InqCircuitBreakerConfigBuilder<?, ?> inqBuilder) {
    this.inqBuilder = inqBuilder;
  }

  @Override
  public CircuitBreakerProtection named(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Circuit Breaker name must not be blank");
    }
    this.name = name;
    return this;
  }

  @Override
  public CircuitBreakerProtection trippingAtThreshold(int threshold) {
    this.failureThreshold = threshold;
    return this;
  }

  @Override
  public CircuitBreakerProtection waitingInOpenStateFor(Duration waitDuration) {
    this.waitDurationInOpenState = waitDuration;
    return this;
  }

  @Override
  public CircuitBreakerProtection permittingCallsInHalfOpen(int permittedCalls) {
    this.permittedNumberOfCallsInHalfOpenState = permittedCalls;
    return this;
  }

  @Override
  public CircuitBreakerProtection evaluatedBy(FailureMetricsConfig metricsConfig) {
    this.metricsConfig = metricsConfig;
    return this;
  }

  @Override
  public CircuitBreakerProtection evaluatedBy(FailureTrackingStrategy.Builder strategyBuilder) {
    this.metricsConfig = strategyBuilder.apply();
    return this;
  }

  @SafeVarargs
  @Override
  public final CircuitBreakerProtection failingOn(Class<? extends Throwable>... exceptions) {
    this.recordedExceptions = Arrays.asList(exceptions);
    return this;
  }

  @SafeVarargs
  @Override
  public final CircuitBreakerProtection ignoringOn(Class<? extends Throwable>... exceptions) {
    this.ignoredExceptions = Arrays.asList(exceptions);
    return this;
  }

  @Override
  public CircuitBreakerConfig apply() {
    if (metricsConfig == null) {
      throw new IllegalStateException("A FailureMetricsConfig must be provided via evaluatedBy()");
    }
    return new CircuitBreakerConfig(
        failureThreshold,
        waitDurationInOpenState,
        permittedNumberOfCallsInHalfOpenState,
        metricsConfig,
        recordedExceptions,
        ignoredExceptions,
        createInqConfig()
    );
  }

  private InqConfig createInqConfig() {
    var hub = InqConfig.configure()
        .general()
        .with(inqBuilder, b -> b
            .waitDurationInOpenState(waitDurationInOpenState)
            .permittedCallsInHalfOpen(permittedNumberOfCallsInHalfOpenState)
        )
        .with(failurePredicate(), b -> b
            .recordExceptions(recordedExceptions)
            .ignoreExceptions(ignoredExceptions)
        );

    if (metricsConfig instanceof SlidingWindowConfig cfg) {
      var maxFailuresInWindow = cfg.maxFailuresInWindow() < 0 ? this.failureThreshold : cfg.maxFailuresInWindow();
      hub = hub.with(new SlidingWindowConfigBuilder(), b -> b
          .maxFailuresInWindow(maxFailuresInWindow)
          .windowSize(cfg.windowSize())
          .minimumNumberOfCalls(cfg.minimumNumberOfCalls())
      );
    } else if (metricsConfig instanceof TimeBasedSlidingWindowConfig cfg) {
      var maxFailuresInWindow = cfg.maxFailuresInWindow() < 0 ? this.failureThreshold : cfg.maxFailuresInWindow();
      hub = hub.with(new TimeBasedSlidingWindowConfigBuilder(), b -> b
          .maxFailuresInWindow(maxFailuresInWindow)
          .windowSizeInSeconds(cfg.windowSizeInSeconds())
      );
    }

    return hub.build();
  }
}
