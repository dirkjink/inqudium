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
  public CircuitBreakerCountEvaluation evaluatingByCountingCalls() {
    return new CountEvaluationBuilder();
  }

  @Override
  public CircuitBreakerTimeEvaluation evaluatingByTimeWindow() {
    return new TimeEvaluationBuilder();
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

  private InqConfig createInqConfig() {
    if (inqBuilder == null) return null;

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
      hub = hub.with(new SlidingWindowConfigBuilder(), b -> b
          .maxFailuresInWindow(failureThreshold)
          .windowSize(cfg.windowSize())
          .minimumNumberOfCalls(cfg.minimumNumberOfCalls())
      );
    } else if (metricsConfig instanceof TimeBasedSlidingWindowConfig cfg) {
      hub = hub.with(new TimeBasedSlidingWindowConfigBuilder(), b -> b
          .maxFailuresInWindow(failureThreshold)
          .windowSizeInSeconds(cfg.windowSizeInSeconds())
      );
    }

    return hub.build();
  }

  private CircuitBreakerConfig buildFinalConfig(FailureMetricsConfig metricsConfig) {
    return new CircuitBreakerConfig(
        name,
        failureThreshold,
        waitDurationInOpenState,
        permittedNumberOfCallsInHalfOpenState,
        List.copyOf(recordedExceptions),
        List.copyOf(ignoredExceptions),
        metricsConfig,
        createInqConfig()
    );
  }

  private class CountEvaluationBuilder implements CircuitBreakerCountEvaluation {
    private int windowSize = 50;
    private int minimumCalls = 20;

    @Override
    public CircuitBreakerCountEvaluation keepingHistoryOf(int numberOfCalls) {
      this.windowSize = numberOfCalls;
      return this;
    }

    @Override
    public CircuitBreakerCountEvaluation requiringAtLeast(int minimumCalls) {
      this.minimumCalls = minimumCalls;
      return this;
    }

    @Override
    public CircuitBreakerConfig applyBalancedProfile() {
      return buildFinalConfig(new SlidingWindowConfig(50, 20));
    }

    @Override
    public CircuitBreakerConfig applyProtectiveProfile() { return buildFinalConfig(new SlidingWindowConfig(10, 5)); }

    @Override
    public CircuitBreakerConfig applyPermissiveProfile() { return buildFinalConfig(new SlidingWindowConfig(200, 100)); }

    @Override
    public CircuitBreakerConfig apply() {
      return buildFinalConfig(new SlidingWindowConfig(windowSize, minimumCalls));
    }
  }

  private class TimeEvaluationBuilder implements CircuitBreakerTimeEvaluation {
    private int seconds = 60;

    @Override
    public CircuitBreakerTimeEvaluation lookingAtTheLast(int seconds) {
      this.seconds = seconds;
      return this;
    }

    @Override
    public CircuitBreakerConfig applyBalancedProfile() { return buildFinalConfig(new TimeBasedSlidingWindowConfig(60)); }

    @Override
    public CircuitBreakerConfig applyProtectiveProfile() { return buildFinalConfig(new TimeBasedSlidingWindowConfig(5)); }

    @Override
    public CircuitBreakerConfig applyPermissiveProfile() { return buildFinalConfig(new TimeBasedSlidingWindowConfig(300)); }

    @Override
    public CircuitBreakerConfig apply() { return buildFinalConfig(new TimeBasedSlidingWindowConfig(seconds)); }
  }
}
