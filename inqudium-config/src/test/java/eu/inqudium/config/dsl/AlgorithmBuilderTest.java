package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Coverage for the algorithm sub-builders ({@link AimdAlgorithmBuilder},
 * {@link VegasAlgorithmBuilder}). The builders are reachable only through
 * {@link AdaptiveConfigBuilder} / {@link AdaptiveNonBlockingConfigBuilder}; tests therefore
 * route through {@code AdaptiveConfigBuilder} as the public entry point and inspect the
 * resulting algorithm config.
 */
@DisplayName("Adaptive algorithm sub-builders")
class AlgorithmBuilderTest {

    private static AimdLimitAlgorithmConfig buildAimd(
            java.util.function.Consumer<AimdAlgorithmBuilder> mutator) {
        AdaptiveConfigBuilder adaptive = new AdaptiveConfigBuilder();
        adaptive.aimd(mutator);
        AdaptiveStrategyConfig built = adaptive.build();
        return (AimdLimitAlgorithmConfig) built.algorithm();
    }

    private static VegasLimitAlgorithmConfig buildVegas(
            java.util.function.Consumer<VegasAlgorithmBuilder> mutator) {
        AdaptiveConfigBuilder adaptive = new AdaptiveConfigBuilder();
        adaptive.vegas(mutator);
        AdaptiveStrategyConfig built = adaptive.build();
        return (VegasLimitAlgorithmConfig) built.algorithm();
    }

    @Nested
    @DisplayName("AIMD defaults")
    class AimdDefaults {

        @Test
        void empty_block_should_produce_balanced_baseline() {
            // What is to be tested: the AimdAlgorithmBuilder's default state matches the
            // deprecated phase-1 balanced() preset values, so a user who writes
            // .aimd(x -> {}) gets a usable algorithm.

            AimdLimitAlgorithmConfig config = buildAimd(x -> { });

            assertThat(config.initialLimit()).isEqualTo(50);
            assertThat(config.minLimit()).isEqualTo(5);
            assertThat(config.maxLimit()).isEqualTo(500);
            assertThat(config.backoffRatio()).isEqualTo(0.7);
            assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(2));
            assertThat(config.errorRateThreshold()).isEqualTo(0.1);
            assertThat(config.windowedIncrease()).isTrue();
            assertThat(config.minUtilizationThreshold()).isEqualTo(0.6);
        }
    }

    @Nested
    @DisplayName("AIMD setters")
    class AimdSetters {

        @Test
        void should_carry_every_field_through_to_the_built_config() {
            AimdLimitAlgorithmConfig config = buildAimd(x -> x
                    .initialLimit(20)
                    .minLimit(3)
                    .maxLimit(123)
                    .backoffRatio(0.4)
                    .smoothingTimeConstant(Duration.ofSeconds(3))
                    .errorRateThreshold(0.25)
                    .windowedIncrease(false)
                    .minUtilizationThreshold(0.42));

            assertThat(config.initialLimit()).isEqualTo(20);
            assertThat(config.minLimit()).isEqualTo(3);
            assertThat(config.maxLimit()).isEqualTo(123);
            assertThat(config.backoffRatio()).isEqualTo(0.4);
            assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(3));
            assertThat(config.errorRateThreshold()).isEqualTo(0.25);
            assertThat(config.windowedIncrease()).isFalse();
            assertThat(config.minUtilizationThreshold()).isEqualTo(0.42);
        }

        @Test
        void should_reject_zero_initialLimit_at_the_setter() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> buildAimd(x -> x.initialLimit(0)))
                    .withMessageContaining("initialLimit");
        }

        @Test
        void should_reject_backoffRatio_outside_unit_interval() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> buildAimd(x -> x.backoffRatio(0.0)))
                    .withMessageContaining("backoffRatio");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> buildAimd(x -> x.backoffRatio(1.5)))
                    .withMessageContaining("backoffRatio");
        }

        @Test
        void should_reject_null_smoothingTimeConstant() {
            assertThatNullPointerException()
                    .isThrownBy(() -> buildAimd(x -> x.smoothingTimeConstant(null)))
                    .withMessageContaining("smoothingTimeConstant");
        }
    }

    @Nested
    @DisplayName("Vegas defaults")
    class VegasDefaults {

        @Test
        void empty_block_should_produce_balanced_baseline() {
            VegasLimitAlgorithmConfig config = buildVegas(v -> { });

            assertThat(config.initialLimit()).isEqualTo(50);
            assertThat(config.minLimit()).isEqualTo(5);
            assertThat(config.maxLimit()).isEqualTo(500);
            assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.baselineDriftTimeConstant()).isEqualTo(Duration.ofSeconds(10));
            assertThat(config.errorRateSmoothingTimeConstant())
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(config.errorRateThreshold()).isEqualTo(0.1);
            assertThat(config.minUtilizationThreshold()).isEqualTo(0.6);
        }
    }

    @Nested
    @DisplayName("Vegas setters")
    class VegasSetters {

        @Test
        void should_carry_every_field_through_to_the_built_config() {
            VegasLimitAlgorithmConfig config = buildVegas(v -> v
                    .initialLimit(20)
                    .minLimit(3)
                    .maxLimit(123)
                    .smoothingTimeConstant(Duration.ofMillis(750))
                    .baselineDriftTimeConstant(Duration.ofMillis(1500))
                    .errorRateSmoothingTimeConstant(Duration.ofMillis(2500))
                    .errorRateThreshold(0.25)
                    .minUtilizationThreshold(0.42));

            assertThat(config.initialLimit()).isEqualTo(20);
            assertThat(config.minLimit()).isEqualTo(3);
            assertThat(config.maxLimit()).isEqualTo(123);
            assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofMillis(750));
            assertThat(config.baselineDriftTimeConstant())
                    .isEqualTo(Duration.ofMillis(1500));
            assertThat(config.errorRateSmoothingTimeConstant())
                    .isEqualTo(Duration.ofMillis(2500));
            assertThat(config.errorRateThreshold()).isEqualTo(0.25);
            assertThat(config.minUtilizationThreshold()).isEqualTo(0.42);
        }

        @Test
        void should_reject_null_baselineDriftTimeConstant_at_the_setter() {
            assertThatNullPointerException()
                    .isThrownBy(() -> buildVegas(v -> v.baselineDriftTimeConstant(null)))
                    .withMessageContaining("baselineDriftTimeConstant");
        }
    }
}
