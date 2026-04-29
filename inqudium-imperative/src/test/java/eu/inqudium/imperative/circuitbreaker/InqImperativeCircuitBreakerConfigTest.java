package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.config.InqConfig;
import org.junit.jupiter.api.Test;

import static eu.inqudium.imperative.circuitbreaker.config.InqImperativeCircuitBreakerConfigBuilder.circuitBreaker;

public class InqImperativeCircuitBreakerConfigTest {

    @Test
    public void test() {
        InqConfig config = InqConfig.configure()
                .general(c -> c
                        .enableExceptionOptimization(true)
                )
                .with(circuitBreaker(), c -> c
                        .successThresholdInHalfOpen(5)
                        .permittedCallsInHalfOpen(10)
                        .name("circuitBreaker-1")
                        .withRecordFailurePredicates(f -> f
                                .ignoreExceptions(IllegalStateException.class)
                                .recordExceptions(OutOfMemoryError.class)
                        ))
                .build();
    }

}
