package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.circuitbreaker.config.*;
import eu.inqudium.core.element.circuitbreaker.metrics.*;
import eu.inqudium.core.element.config.FailurePredicateConfig;
import eu.inqudium.imperative.circuitbreaker.config.InqImperativeCircuitBreakerConfig;

import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class CircuitBreakerFactory {

    public <A, R> ImperativeCircuitBreaker<A, R> create(InqConfig inqConfig) {
        LongFunction<FailureMetrics> metricsFactory = createFailureMetricsFactory(inqConfig);
        Predicate<Throwable> recordFailurePredicate = createFailurePredicate(inqConfig);
        return inqConfig.of(InqImperativeCircuitBreakerConfig.class)
                .map(InqImperativeCircuitBreakerConfig::circuitBreaker)
                .map((c) ->
                        new ImperativeCircuitBreaker<A, R>(c, metricsFactory, recordFailurePredicate))
                .orElseThrow();
    }

    private LongFunction<FailureMetrics> createFailureMetricsFactory(InqConfig inqConfig) {
        return
                inqConfig.of(InqImperativeCircuitBreakerConfig.class)
                        .map(InqImperativeCircuitBreakerConfig::circuitBreaker)
                        .map(InqCircuitBreakerConfig::metricsFactory)
                        .orElseGet(() -> {
                            List<FailureMetrics> failureMetricsList = Stream.of(
                                            createConsecutiveFailuresMetrics(inqConfig),
                                            createContinuousTimeEwmaMetrics(inqConfig),
                                            createGradualDecayMetrics(inqConfig),
                                            createLeakyBucketMetrics(inqConfig),
                                            createRequestBasedEwmaMetrics(inqConfig),
                                            createSlidingWindowMetrics(inqConfig),
                                            createTimeBasedErrorRateMetrics(inqConfig),
                                            createTimeBasedSlidingWindowMetrics(inqConfig)
                                    )
                                    .filter(Objects::nonNull)
                                    .toList();
                            final LongFunction<FailureMetrics> metricsFactory;
                            if (failureMetricsList.isEmpty()) {
                                SlidingWindowConfig c = new SlidingWindowConfigBuilder().balanced().build();
                                return SlidingWindowMetrics.initial(
                                        c.maxFailuresInWindow(),
                                        c.windowSize(),
                                        c.minimumNumberOfCalls()).metricsFactory();
                            } else {
                                if (failureMetricsList.size() == 1) {
                                    metricsFactory = failureMetricsList.getFirst().metricsFactory();
                                } else {
                                    metricsFactory = new CompositeFailureMetrics(failureMetricsList).metricsFactory();
                                }
                            }
                            return metricsFactory;
                        });
    }

    private Predicate<Throwable> createFailurePredicate(InqConfig inqConfig) {
        return inqConfig.of(InqImperativeCircuitBreakerConfig.class)
                .map(InqImperativeCircuitBreakerConfig::circuitBreaker)
                .map(InqCircuitBreakerConfig::recordFailurePredicate)
                .orElseGet(() -> inqConfig.of(FailurePredicateConfig.class)
                        .map(FailurePredicateConfig::finalPredicate)
                        .orElse(null)
                );
    }


    private FailureMetrics createConsecutiveFailuresMetrics(InqConfig inqConfig) {
        return inqConfig.of(ConsecutiveFailuresConfig.class)
                .map(c ->
                        ConsecutiveFailuresMetrics.initial(
                                c.maxConsecutiveFailures(),
                                c.initialConsecutiveFailures())
                )
                .orElse(null);
    }

    private FailureMetrics createContinuousTimeEwmaMetrics(InqConfig inqConfig) {
        return inqConfig.of(ContinuousTimeEwmaConfig.class)
                .map(c ->
                        ContinuousTimeEwmaMetrics.initial(
                                c.failureRatePercent(),
                                c.timeConstant(),
                                c.minimumNumberOfCalls(),
                                inqConfig.general().nanoTimesource().now())
                )
                .orElse(null);
    }

    private FailureMetrics createGradualDecayMetrics(InqConfig inqConfig) {
        return inqConfig.of(GradualDecayConfig.class)
                .map(c ->
                        GradualDecayMetrics.initial(
                                c.maxFailureCount(),
                                c.initialFailureCount())
                )
                .orElse(null);
    }

    private FailureMetrics createLeakyBucketMetrics(InqConfig inqConfig) {
        return inqConfig.of(LeakyBucketConfig.class)
                .map(c ->
                        LeakyBucketMetrics.initial(
                                c.bucketCapacity(),
                                c.leakRatePerSecond(),
                                inqConfig.general().nanoTimesource().now())
                )
                .orElse(null);
    }

    private FailureMetrics createRequestBasedEwmaMetrics(InqConfig inqConfig) {
        return inqConfig.of(RequestBasedEwmaConfig.class)
                .map(c ->
                        RequestBasedEwmaMetrics.initial(
                                c.failureRatePercent(),
                                c.smoothingFactor(),
                                c.minimumNumberOfCalls())
                )
                .orElse(null);
    }

    private FailureMetrics createSlidingWindowMetrics(InqConfig inqConfig) {
        return inqConfig.of(SlidingWindowConfig.class)
                .map(c ->
                        SlidingWindowMetrics.initial(
                                c.maxFailuresInWindow(),
                                c.windowSize(),
                                c.minimumNumberOfCalls())
                )
                .orElse(null);
    }

    private FailureMetrics createTimeBasedErrorRateMetrics(InqConfig inqConfig) {
        return inqConfig.of(TimeBasedErrorRateConfig.class)
                .map(c ->
                        TimeBasedErrorRateMetrics.initial(
                                c.failureRatePercent(),
                                c.windowSizeInSeconds(),
                                c.minimumNumberOfCalls(),
                                inqConfig.general().nanoTimesource().now())
                )
                .orElse(null);
    }

    private FailureMetrics createTimeBasedSlidingWindowMetrics(InqConfig inqConfig) {
        return inqConfig.of(TimeBasedSlidingWindowConfig.class)
                .map(c ->
                        TimeBasedSlidingWindowMetrics.initial(
                                c.maxFailuresInWindow(),
                                c.windowSizeInSeconds(),
                                inqConfig.general().nanoTimesource().now())
                )
                .orElse(null);
    }
}
