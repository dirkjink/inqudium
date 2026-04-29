package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Coverage for the {@code protective} / {@code balanced} / {@code permissive} presets exposed
 * by {@link AimdAlgorithmBuilder} and {@link VegasAlgorithmBuilder}.
 *
 * <h2>Drift watch</h2>
 *
 * <p>The {@code PresetValuesMatchLegacyFactory} blocks pin the resulting algorithm-config
 * fields against the values that
 * {@link eu.inqudium.core.element.bulkhead.algo.AimdLimitAlgorithm#protective()},
 * {@link eu.inqudium.core.element.bulkhead.algo.AimdLimitAlgorithm#balanced()},
 * {@link eu.inqudium.core.element.bulkhead.algo.AimdLimitAlgorithm#permissive()} (and the Vegas
 * counterparts) actually set. Because the algorithm classes hold their tuning fields as
 * {@code private final} without accessors — and several of them are further encapsulated inside
 * a {@code ContinuousTimeEwma} — the values cannot be read off a constructed algorithm object.
 * The Single Source of Truth is therefore the Javadoc {@code <table>} block of each static
 * factory method. The tests transcribe those tables literally; if either side drifts (the
 * factory method, the Javadoc table, or the sub-builder preset), the assertion fails.
 */
@DisplayName("Algorithm sub-builder presets")
class AlgorithmBuilderPresetsTest {

    private static AimdLimitAlgorithmConfig buildAimd(Consumer<AimdAlgorithmBuilder> mutator) {
        AdaptiveConfigBuilder adaptive = new AdaptiveConfigBuilder();
        adaptive.aimd(mutator);
        AdaptiveStrategyConfig built = adaptive.build();
        return (AimdLimitAlgorithmConfig) built.algorithm();
    }

    private static VegasLimitAlgorithmConfig buildVegas(Consumer<VegasAlgorithmBuilder> mutator) {
        AdaptiveConfigBuilder adaptive = new AdaptiveConfigBuilder();
        adaptive.vegas(mutator);
        AdaptiveStrategyConfig built = adaptive.build();
        return (VegasLimitAlgorithmConfig) built.algorithm();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // AIMD
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AIMD")
    class Aimd {

        @Nested
        @DisplayName("Preset values match the legacy factory")
        class PresetValuesMatchLegacyFactory {

            @Test
            void protective_should_carry_the_documented_values() {
                // Given / When
                AimdLimitAlgorithmConfig config = buildAimd(AimdAlgorithmBuilder::protective);

                // Then — values transcribed from AimdLimitAlgorithm.protective()'s Javadoc table.
                assertThat(config.initialLimit()).isEqualTo(20);
                assertThat(config.minLimit()).isEqualTo(1);
                assertThat(config.maxLimit()).isEqualTo(200);
                assertThat(config.backoffRatio()).isEqualTo(0.5);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(5));
                assertThat(config.errorRateThreshold()).isEqualTo(0.15);
                assertThat(config.windowedIncrease()).isTrue();
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.5);
            }

            @Test
            void balanced_should_carry_the_documented_values() {
                // Given / When
                AimdLimitAlgorithmConfig config = buildAimd(AimdAlgorithmBuilder::balanced);

                // Then — values transcribed from AimdLimitAlgorithm.balanced()'s Javadoc table.
                assertThat(config.initialLimit()).isEqualTo(50);
                assertThat(config.minLimit()).isEqualTo(5);
                assertThat(config.maxLimit()).isEqualTo(500);
                assertThat(config.backoffRatio()).isEqualTo(0.7);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(2));
                assertThat(config.errorRateThreshold()).isEqualTo(0.1);
                assertThat(config.windowedIncrease()).isTrue();
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.6);
            }

            @Test
            void permissive_should_carry_the_documented_values() {
                // Given / When
                AimdLimitAlgorithmConfig config = buildAimd(AimdAlgorithmBuilder::permissive);

                // Then — values transcribed from AimdLimitAlgorithm.permissive()'s Javadoc table.
                assertThat(config.initialLimit()).isEqualTo(100);
                assertThat(config.minLimit()).isEqualTo(10);
                assertThat(config.maxLimit()).isEqualTo(1000);
                assertThat(config.backoffRatio()).isEqualTo(0.85);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(1));
                assertThat(config.errorRateThreshold()).isEqualTo(0.05);
                assertThat(config.windowedIncrease()).isFalse();
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.75);
            }
        }

        @Nested
        @DisplayName("Preset followed by individual setter — preset is the baseline")
        class PresetThenCustomize {

            @Test
            void protective_then_maxLimit_should_overwrite_only_maxLimit() {
                // Given / When
                AimdLimitAlgorithmConfig config = buildAimd(x -> x.protective().maxLimit(150));

                // Then — maxLimit is the user's override, every other field stays on protective.
                assertThat(config.maxLimit()).isEqualTo(150);
                assertThat(config.initialLimit()).isEqualTo(20);
                assertThat(config.minLimit()).isEqualTo(1);
                assertThat(config.backoffRatio()).isEqualTo(0.5);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(5));
                assertThat(config.errorRateThreshold()).isEqualTo(0.15);
                assertThat(config.windowedIncrease()).isTrue();
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.5);
            }

            @Test
            void balanced_then_backoffRatio_should_overwrite_only_backoffRatio() {
                // Given / When
                AimdLimitAlgorithmConfig config = buildAimd(x -> x.balanced().backoffRatio(0.55));

                // Then
                assertThat(config.backoffRatio()).isEqualTo(0.55);
                assertThat(config.initialLimit()).isEqualTo(50);
                assertThat(config.minLimit()).isEqualTo(5);
                assertThat(config.maxLimit()).isEqualTo(500);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(2));
                assertThat(config.errorRateThreshold()).isEqualTo(0.1);
                assertThat(config.windowedIncrease()).isTrue();
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.6);
            }

            @Test
            void permissive_then_windowedIncrease_should_overwrite_only_windowedIncrease() {
                // Given / When
                AimdLimitAlgorithmConfig config = buildAimd(x -> x.permissive().windowedIncrease(true));

                // Then
                assertThat(config.windowedIncrease()).isTrue();
                assertThat(config.initialLimit()).isEqualTo(100);
                assertThat(config.minLimit()).isEqualTo(10);
                assertThat(config.maxLimit()).isEqualTo(1000);
                assertThat(config.backoffRatio()).isEqualTo(0.85);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(1));
                assertThat(config.errorRateThreshold()).isEqualTo(0.05);
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.75);
            }
        }

        @Nested
        @DisplayName("Individual setter followed by preset — rejected")
        class CustomizeThenPreset {

            @Test
            void maxLimit_then_protective_should_throw() {
                // Given / When / Then — the guard mirrors the top-level builder discipline:
                // presets are baselines and must come before per-field setters, otherwise the
                // user's preceding override would be silently clobbered.
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> buildAimd(x -> x.maxLimit(150).protective()));
            }

            @Test
            void backoffRatio_then_balanced_should_throw() {
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> buildAimd(x -> x.backoffRatio(0.55).balanced()));
            }

            @Test
            void initialLimit_then_permissive_should_throw() {
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> buildAimd(x -> x.initialLimit(7).permissive()));
            }

            @Test
            void exception_message_should_explain_preset_then_customize_order() {
                // What is to be tested: the exception message is actionable — it tells the user
                // *what* the rule is and *how* to fix the call.
                // How will it be deemed successful: the message contains the signature phrasing
                // ("Cannot apply a preset after individual setters") and an example showing the
                // correct order.
                // Why important: the guard fires far from the invalid call site; without a clear
                // message the user just sees an IllegalStateException with no remediation hint.
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> buildAimd(x -> x.maxLimit(150).protective()))
                        .withMessageContaining("Cannot apply a preset after individual setters")
                        .withMessageContaining("Presets are baselines")
                        .withMessageContaining(".aimd(");
            }
        }

        @Nested
        @DisplayName("Empty builder — balanced defaults survive")
        class EmptyBuilderStillBalancedDefaults {

            @Test
            void empty_lambda_should_produce_balanced_baseline() {
                // What is to be tested: the construction-time field defaults of
                // AimdAlgorithmBuilder match the balanced-preset values, so .aimd(x -> {})
                // (no preset call, no setter call) still yields a balanced-conformant config.
                // Why important: this is the regression anchor for 2.10.C's claim that the
                // empty-lambda path remains unchanged after preset methods were added on top.

                // Given / When
                AimdLimitAlgorithmConfig config = buildAimd(x -> { });

                // Then
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
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Vegas
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Vegas")
    class Vegas {

        @Nested
        @DisplayName("Preset values match the legacy factory")
        class PresetValuesMatchLegacyFactory {

            @Test
            void protective_should_carry_the_documented_values() {
                // Given / When
                VegasLimitAlgorithmConfig config = buildVegas(VegasAlgorithmBuilder::protective);

                // Then — values transcribed from VegasLimitAlgorithm.protective()'s Javadoc table.
                assertThat(config.initialLimit()).isEqualTo(20);
                assertThat(config.minLimit()).isEqualTo(1);
                assertThat(config.maxLimit()).isEqualTo(200);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(2));
                assertThat(config.baselineDriftTimeConstant()).isEqualTo(Duration.ofSeconds(30));
                assertThat(config.errorRateSmoothingTimeConstant())
                        .isEqualTo(Duration.ofSeconds(10));
                assertThat(config.errorRateThreshold()).isEqualTo(0.15);
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.5);
            }

            @Test
            void balanced_should_carry_the_documented_values() {
                // Given / When
                VegasLimitAlgorithmConfig config = buildVegas(VegasAlgorithmBuilder::balanced);

                // Then — values transcribed from VegasLimitAlgorithm.balanced()'s Javadoc table.
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

            @Test
            void permissive_should_carry_the_documented_values() {
                // Given / When
                VegasLimitAlgorithmConfig config = buildVegas(VegasAlgorithmBuilder::permissive);

                // Then — values transcribed from VegasLimitAlgorithm.permissive()'s Javadoc table.
                assertThat(config.initialLimit()).isEqualTo(100);
                assertThat(config.minLimit()).isEqualTo(10);
                assertThat(config.maxLimit()).isEqualTo(1000);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofMillis(500));
                assertThat(config.baselineDriftTimeConstant()).isEqualTo(Duration.ofSeconds(5));
                assertThat(config.errorRateSmoothingTimeConstant())
                        .isEqualTo(Duration.ofSeconds(3));
                assertThat(config.errorRateThreshold()).isEqualTo(0.05);
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.75);
            }
        }

        @Nested
        @DisplayName("Preset followed by individual setter — preset is the baseline")
        class PresetThenCustomize {

            @Test
            void protective_then_maxLimit_should_overwrite_only_maxLimit() {
                // Given / When
                VegasLimitAlgorithmConfig config = buildVegas(v -> v.protective().maxLimit(150));

                // Then
                assertThat(config.maxLimit()).isEqualTo(150);
                assertThat(config.initialLimit()).isEqualTo(20);
                assertThat(config.minLimit()).isEqualTo(1);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(2));
                assertThat(config.baselineDriftTimeConstant()).isEqualTo(Duration.ofSeconds(30));
                assertThat(config.errorRateSmoothingTimeConstant())
                        .isEqualTo(Duration.ofSeconds(10));
                assertThat(config.errorRateThreshold()).isEqualTo(0.15);
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.5);
            }

            @Test
            void balanced_then_errorRateThreshold_should_overwrite_only_errorRateThreshold() {
                // Given / When
                VegasLimitAlgorithmConfig config = buildVegas(
                        v -> v.balanced().errorRateThreshold(0.2));

                // Then
                assertThat(config.errorRateThreshold()).isEqualTo(0.2);
                assertThat(config.initialLimit()).isEqualTo(50);
                assertThat(config.minLimit()).isEqualTo(5);
                assertThat(config.maxLimit()).isEqualTo(500);
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofSeconds(1));
                assertThat(config.baselineDriftTimeConstant()).isEqualTo(Duration.ofSeconds(10));
                assertThat(config.errorRateSmoothingTimeConstant())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.6);
            }

            @Test
            void permissive_then_smoothingTimeConstant_should_overwrite_only_smoothingTimeConstant() {
                // Given / When
                VegasLimitAlgorithmConfig config = buildVegas(
                        v -> v.permissive().smoothingTimeConstant(Duration.ofMillis(750)));

                // Then
                assertThat(config.smoothingTimeConstant()).isEqualTo(Duration.ofMillis(750));
                assertThat(config.initialLimit()).isEqualTo(100);
                assertThat(config.minLimit()).isEqualTo(10);
                assertThat(config.maxLimit()).isEqualTo(1000);
                assertThat(config.baselineDriftTimeConstant()).isEqualTo(Duration.ofSeconds(5));
                assertThat(config.errorRateSmoothingTimeConstant())
                        .isEqualTo(Duration.ofSeconds(3));
                assertThat(config.errorRateThreshold()).isEqualTo(0.05);
                assertThat(config.minUtilizationThreshold()).isEqualTo(0.75);
            }
        }

        @Nested
        @DisplayName("Individual setter followed by preset — rejected")
        class CustomizeThenPreset {

            @Test
            void maxLimit_then_protective_should_throw() {
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> buildVegas(v -> v.maxLimit(150).protective()));
            }

            @Test
            void errorRateThreshold_then_balanced_should_throw() {
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> buildVegas(v -> v.errorRateThreshold(0.2).balanced()));
            }

            @Test
            void initialLimit_then_permissive_should_throw() {
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> buildVegas(v -> v.initialLimit(7).permissive()));
            }

            @Test
            void exception_message_should_explain_preset_then_customize_order() {
                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> buildVegas(v -> v.maxLimit(150).protective()))
                        .withMessageContaining("Cannot apply a preset after individual setters")
                        .withMessageContaining("Presets are baselines")
                        .withMessageContaining(".vegas(");
            }
        }

        @Nested
        @DisplayName("Empty builder — balanced defaults survive")
        class EmptyBuilderStillBalancedDefaults {

            @Test
            void empty_lambda_should_produce_balanced_baseline() {
                // Given / When
                VegasLimitAlgorithmConfig config = buildVegas(v -> { });

                // Then — same field set as the balanced() preset above.
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
    }
}
