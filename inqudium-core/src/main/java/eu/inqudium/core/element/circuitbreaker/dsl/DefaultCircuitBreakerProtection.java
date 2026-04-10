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
    private Integer failureThreshold;
    private Duration waitDurationInOpenState = Duration.ofSeconds(60);
    private Integer permittedNumberOfCallsInHalfOpenState = 10;
    private List<Class<? extends Throwable>> recordedExceptions = List.of(Exception.class);
    private List<Class<? extends Throwable>> ignoredExceptions = List.of();
    private FailureMetricsConfig metricsConfig;

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
        this.metricsConfig = metricsConfig;
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
            var cfg = new SlidingWindowConfigBuilder().balanced().build();
            if (failureThreshold == null) failureThreshold = cfg.maxFailuresInWindow();
            return buildFinalConfig(new SlidingWindowConfig(cfg.windowSize(), cfg.minimumNumberOfCalls()));
        }

        @Override
        public CircuitBreakerConfig applyProtectiveProfile() {
            var cfg = new SlidingWindowConfigBuilder().protective().build();
            if (failureThreshold == null) failureThreshold = cfg.maxFailuresInWindow();
            return buildFinalConfig(new SlidingWindowConfig(cfg.windowSize(), cfg.minimumNumberOfCalls()));
        }

        @Override
        public CircuitBreakerConfig applyPermissiveProfile() {
            var cfg = new SlidingWindowConfigBuilder().permissive().build();
            if (failureThreshold == null) failureThreshold = cfg.maxFailuresInWindow();
            return buildFinalConfig(new SlidingWindowConfig(cfg.windowSize(), cfg.minimumNumberOfCalls()));
        }

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
        public CircuitBreakerConfig applyBalancedProfile() {
            var cfg = new TimeBasedSlidingWindowConfigBuilder().balanced().build();
            if (failureThreshold == null) failureThreshold = cfg.maxFailuresInWindow();
            return buildFinalConfig(new TimeBasedSlidingWindowConfig(cfg.windowSizeInSeconds()));
        }

        @Override
        public CircuitBreakerConfig applyProtectiveProfile() {
            var cfg = new TimeBasedSlidingWindowConfigBuilder().protective().build();
            if (failureThreshold == null) failureThreshold = cfg.maxFailuresInWindow();
            return buildFinalConfig(new TimeBasedSlidingWindowConfig(cfg.windowSizeInSeconds()));
        }

        @Override
        public CircuitBreakerConfig applyPermissiveProfile() {
            var cfg = new TimeBasedSlidingWindowConfigBuilder().permissive().build();
            if (failureThreshold == null) failureThreshold = cfg.maxFailuresInWindow();
            return buildFinalConfig(new TimeBasedSlidingWindowConfig(cfg.windowSizeInSeconds()));
        }

        @Override
        public CircuitBreakerConfig apply() {
            return buildFinalConfig(new TimeBasedSlidingWindowConfig(seconds));
        }
    }
}
