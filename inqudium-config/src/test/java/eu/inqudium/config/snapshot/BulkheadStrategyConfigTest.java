package eu.inqudium.config.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Compact-constructor invariants and basic identity for the sealed
 * {@link BulkheadStrategyConfig} hierarchy introduced in REFACTORING.md 2.10.A.
 */
@DisplayName("BulkheadStrategyConfig hierarchy")
class BulkheadStrategyConfigTest {

    @Nested
    @DisplayName("SemaphoreStrategyConfig")
    class Semaphore {

        @Test
        void should_construct_without_arguments() {
            assertThat(new SemaphoreStrategyConfig()).isNotNull();
        }

        @Test
        void two_instances_should_be_equal() {
            // Records implement value equality automatically; pinning the property because
            // downstream code (snapshot equality, dispatcher UNCHANGED detection) leans on it.
            assertThat(new SemaphoreStrategyConfig())
                    .isEqualTo(new SemaphoreStrategyConfig());
        }
    }

    @Nested
    @DisplayName("CoDelStrategyConfig")
    class CoDel {

        @Test
        void should_carry_targetDelay_and_interval_through_construction() {
            CoDelStrategyConfig config = new CoDelStrategyConfig(
                    Duration.ofMillis(50), Duration.ofMillis(100));

            assertThat(config.targetDelay()).isEqualTo(Duration.ofMillis(50));
            assertThat(config.interval()).isEqualTo(Duration.ofMillis(100));
        }

        @Test
        void should_reject_null_targetDelay() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CoDelStrategyConfig(null, Duration.ofMillis(100)))
                    .withMessageContaining("targetDelay");
        }

        @Test
        void should_reject_null_interval() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CoDelStrategyConfig(Duration.ofMillis(50), null))
                    .withMessageContaining("interval");
        }

        @Test
        void should_reject_zero_targetDelay() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CoDelStrategyConfig(
                            Duration.ZERO, Duration.ofMillis(100)))
                    .withMessageContaining("targetDelay");
        }

        @Test
        void should_reject_negative_targetDelay() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CoDelStrategyConfig(
                            Duration.ofMillis(-1), Duration.ofMillis(100)))
                    .withMessageContaining("targetDelay");
        }

        @Test
        void should_reject_zero_interval() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CoDelStrategyConfig(
                            Duration.ofMillis(50), Duration.ZERO))
                    .withMessageContaining("interval");
        }

        @Test
        void should_reject_negative_interval() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CoDelStrategyConfig(
                            Duration.ofMillis(50), Duration.ofMillis(-1)))
                    .withMessageContaining("interval");
        }
    }

    @Nested
    @DisplayName("AdaptiveStrategyConfig")
    class Adaptive {

        @Test
        void should_carry_the_algorithm_through_construction() {
            LimitAlgorithm algo = aimd();
            AdaptiveStrategyConfig config = new AdaptiveStrategyConfig(algo);

            assertThat(config.algorithm()).isSameAs(algo);
        }

        @Test
        void should_reject_null_algorithm() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdaptiveStrategyConfig(null))
                    .withMessageContaining("algorithm");
        }
    }

    @Nested
    @DisplayName("AdaptiveNonBlockingStrategyConfig")
    class AdaptiveNonBlocking {

        @Test
        void should_carry_the_algorithm_through_construction() {
            LimitAlgorithm algo = vegas();
            AdaptiveNonBlockingStrategyConfig config =
                    new AdaptiveNonBlockingStrategyConfig(algo);

            assertThat(config.algorithm()).isSameAs(algo);
        }

        @Test
        void should_reject_null_algorithm() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdaptiveNonBlockingStrategyConfig(null))
                    .withMessageContaining("algorithm");
        }

        @Test
        void should_be_distinct_from_blocking_variant() {
            // Both wrap the same algorithm but materialize to different strategy
            // implementations; sealed-type pattern matching must distinguish them.
            LimitAlgorithm algo = aimd();
            AdaptiveStrategyConfig blocking = new AdaptiveStrategyConfig(algo);
            AdaptiveNonBlockingStrategyConfig nonBlocking =
                    new AdaptiveNonBlockingStrategyConfig(algo);

            assertThat((BulkheadStrategyConfig) blocking)
                    .isNotEqualTo(nonBlocking);
        }
    }

    @Nested
    @DisplayName("AimdLimitAlgorithmConfig invariants")
    class AimdInvariants {

        @Test
        void should_construct_with_valid_values() {
            AimdLimitAlgorithmConfig config = new AimdLimitAlgorithmConfig(
                    20, 5, 100, 0.9,
                    Duration.ofSeconds(1), 0.05, true, 0.5);

            assertThat(config.initialLimit()).isEqualTo(20);
            assertThat(config.minLimit()).isEqualTo(5);
            assertThat(config.maxLimit()).isEqualTo(100);
            assertThat(config.backoffRatio()).isEqualTo(0.9);
            assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.errorRateThreshold()).isEqualTo(0.05);
            assertThat(config.windowedIncrease()).isTrue();
            assertThat(config.minUtilizationThreshold()).isEqualTo(0.5);
        }

        @Test
        void should_reject_min_above_initial() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AimdLimitAlgorithmConfig(
                            10, 20, 100, 0.9,
                            Duration.ofSeconds(1), 0.05, true, 0.5))
                    .withMessageContaining("minLimit");
        }

        @Test
        void should_reject_max_below_initial() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AimdLimitAlgorithmConfig(
                            50, 5, 10, 0.9,
                            Duration.ofSeconds(1), 0.05, true, 0.5))
                    .withMessageContaining("maxLimit");
        }

        @Test
        void should_reject_backoffRatio_outside_unit_interval() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AimdLimitAlgorithmConfig(
                            10, 5, 100, 0.0,
                            Duration.ofSeconds(1), 0.05, true, 0.5))
                    .withMessageContaining("backoffRatio");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AimdLimitAlgorithmConfig(
                            10, 5, 100, 1.5,
                            Duration.ofSeconds(1), 0.05, true, 0.5))
                    .withMessageContaining("backoffRatio");
        }

        @Test
        void should_reject_null_or_zero_smoothingTimeConstant() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AimdLimitAlgorithmConfig(
                            10, 5, 100, 0.9, null, 0.05, true, 0.5))
                    .withMessageContaining("smoothingTimeConstant");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AimdLimitAlgorithmConfig(
                            10, 5, 100, 0.9,
                            Duration.ZERO, 0.05, true, 0.5))
                    .withMessageContaining("smoothingTimeConstant");
        }
    }

    @Nested
    @DisplayName("VegasLimitAlgorithmConfig invariants")
    class VegasInvariants {

        @Test
        void should_construct_with_valid_values() {
            VegasLimitAlgorithmConfig config = new VegasLimitAlgorithmConfig(
                    20, 5, 100,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                    0.05, 0.5);

            assertThat(config.initialLimit()).isEqualTo(20);
            assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.baselineDriftTimeConstant()).isEqualTo(Duration.ofSeconds(2));
            assertThat(config.errorRateSmoothingTimeConstant()).isEqualTo(Duration.ofSeconds(3));
        }

        @Test
        void should_reject_null_baselineDriftTimeConstant() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new VegasLimitAlgorithmConfig(
                            20, 5, 100,
                            Duration.ofSeconds(1), null, Duration.ofSeconds(3),
                            0.05, 0.5))
                    .withMessageContaining("baselineDriftTimeConstant");
        }

        @Test
        void should_reject_errorRateThreshold_above_one() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new VegasLimitAlgorithmConfig(
                            20, 5, 100,
                            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                            1.5, 0.5))
                    .withMessageContaining("errorRateThreshold");
        }
    }

    private static LimitAlgorithm aimd() {
        return new AimdLimitAlgorithmConfig(
                20, 5, 100, 0.9,
                Duration.ofSeconds(1), 0.05, true, 0.5);
    }

    private static LimitAlgorithm vegas() {
        return new VegasLimitAlgorithmConfig(
                20, 5, 100,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                0.05, 0.5);
    }
}
