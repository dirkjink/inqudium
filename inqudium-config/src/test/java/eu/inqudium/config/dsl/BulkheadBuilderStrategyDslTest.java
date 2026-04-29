package eu.inqudium.config.dsl;

import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.snapshot.AdaptiveNonBlockingStrategyConfig;
import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Coverage for the four strategy setters on {@link BulkheadBuilder}: {@code semaphore()},
 * {@code codel(...)}, {@code adaptive(...)}, {@code adaptiveNonBlocking(...)} (REFACTORING.md
 * 2.10.C). Tests apply the builder's patch to a fixed system-default snapshot and inspect
 * {@code snapshot.strategy()}.
 */
@DisplayName("BulkheadBuilder strategy DSL")
class BulkheadBuilderStrategyDslTest {

    private static final class TestBuilder extends BulkheadBuilderBase<ImperativeTag> {
        TestBuilder(String name) {
            super(name);
        }
    }

    private static BulkheadSnapshot systemDefault() {
        return new BulkheadSnapshot(
                "default-name", 25, Duration.ofMillis(100), Set.of(), null,
                BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());
    }

    private static BulkheadSnapshot snapshotFor(BulkheadBuilder<ImperativeTag> builder) {
        return ((TestBuilder) builder).toPatch().applyTo(systemDefault());
    }

    @Nested
    @DisplayName("default behaviour")
    class DefaultBehaviour {

        @Test
        void should_inherit_SemaphoreStrategyConfig_when_no_strategy_setter_is_called() {
            // What is to be tested: that a builder which never calls semaphore/codel/adaptive
            // produces a patch whose touchedFields does NOT contain STRATEGY, so the snapshot's
            // strategy field inherits from the base. That is the entire reason the base
            // snapshot's default is SemaphoreStrategyConfig.

            TestBuilder b = new TestBuilder("inventory");
            b.balanced();

            BulkheadSnapshot result = snapshotFor(b);

            assertThat(result.strategy()).isInstanceOf(SemaphoreStrategyConfig.class);
        }
    }

    @Nested
    @DisplayName("semaphore()")
    class Semaphore {

        @Test
        void should_explicitly_set_SemaphoreStrategyConfig() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced().semaphore();

            assertThat(snapshotFor(b).strategy()).isInstanceOf(SemaphoreStrategyConfig.class);
        }
    }

    @Nested
    @DisplayName("codel(...)")
    class CoDel {

        @Test
        void should_set_CoDelStrategyConfig_with_the_supplied_targetDelay_and_interval() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced().codel(c -> c
                    .targetDelay(Duration.ofMillis(75))
                    .interval(Duration.ofMillis(750)));

            CoDelStrategyConfig codel = (CoDelStrategyConfig) snapshotFor(b).strategy();
            assertThat(codel.targetDelay()).isEqualTo(Duration.ofMillis(75));
            assertThat(codel.interval()).isEqualTo(Duration.ofMillis(750));
        }

        @Test
        void should_use_balanced_defaults_for_an_empty_codel_block() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced().codel(c -> { });

            CoDelStrategyConfig codel = (CoDelStrategyConfig) snapshotFor(b).strategy();
            assertThat(codel.targetDelay()).isEqualTo(Duration.ofMillis(50));
            assertThat(codel.interval()).isEqualTo(Duration.ofMillis(500));
        }

        @Test
        void should_reject_null_targetDelay_at_the_dsl_setter() {
            TestBuilder b = new TestBuilder("inventory");

            assertThatNullPointerException()
                    .isThrownBy(() -> b.codel(c -> c.targetDelay(null)));
        }

        @Test
        void should_reject_negative_interval_at_the_dsl_setter() {
            TestBuilder b = new TestBuilder("inventory");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> b.codel(c -> c.interval(Duration.ofMillis(-1))))
                    .withMessageContaining("interval");
        }

        @Test
        void should_reject_null_configurer() {
            TestBuilder b = new TestBuilder("inventory");

            assertThatNullPointerException()
                    .isThrownBy(() -> b.codel(null))
                    .withMessageContaining("configurer");
        }
    }

    @Nested
    @DisplayName("adaptive(...) blocking")
    class AdaptiveBlocking {

        @Test
        void should_set_AdaptiveStrategyConfig_with_AIMD_when_aimd_called() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced().adaptive(a -> a.aimd(x -> x
                    .initialLimit(33).minLimit(7).maxLimit(150)));

            AdaptiveStrategyConfig adaptive =
                    (AdaptiveStrategyConfig) snapshotFor(b).strategy();
            AimdLimitAlgorithmConfig aimd =
                    (AimdLimitAlgorithmConfig) adaptive.algorithm();
            assertThat(aimd.initialLimit()).isEqualTo(33);
            assertThat(aimd.minLimit()).isEqualTo(7);
            assertThat(aimd.maxLimit()).isEqualTo(150);
        }

        @Test
        void should_set_AdaptiveStrategyConfig_with_Vegas_when_vegas_called() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced().adaptive(a -> a.vegas(v -> v
                    .smoothingTimeConstant(Duration.ofMillis(750))));

            AdaptiveStrategyConfig adaptive =
                    (AdaptiveStrategyConfig) snapshotFor(b).strategy();
            VegasLimitAlgorithmConfig vegas =
                    (VegasLimitAlgorithmConfig) adaptive.algorithm();
            assertThat(vegas.smoothingTimeConstant()).isEqualTo(Duration.ofMillis(750));
        }

        @Test
        void should_throw_when_neither_aimd_nor_vegas_is_called() {
            // What is to be tested: an empty .adaptive(a -> {}) block fails fast at build time
            // with a message that asks the user to call .aimd(...) or .vegas(...). Why: ADR-032
            // requires the algorithm choice to be explicit; an implicit AIMD default would
            // hide the choice from a future reader.

            TestBuilder b = new TestBuilder("inventory");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> b.adaptive(a -> { }))
                    .withMessageContaining(".aimd(...)")
                    .withMessageContaining(".vegas(...)");
        }

        @Test
        void should_pick_the_last_algorithm_when_both_aimd_and_vegas_are_called() {
            // Last-writer-wins inside the adaptive sub-builder: vegas() after aimd() means the
            // resulting algorithm is Vegas.
            TestBuilder b = new TestBuilder("inventory");
            b.balanced().adaptive(a -> a
                    .aimd(x -> x.initialLimit(99))
                    .vegas(v -> v.initialLimit(33)));

            AdaptiveStrategyConfig adaptive =
                    (AdaptiveStrategyConfig) snapshotFor(b).strategy();
            VegasLimitAlgorithmConfig vegas =
                    (VegasLimitAlgorithmConfig) adaptive.algorithm();
            assertThat(vegas.initialLimit()).isEqualTo(33);
        }
    }

    @Nested
    @DisplayName("adaptiveNonBlocking(...)")
    class AdaptiveNonBlocking {

        @Test
        void should_set_AdaptiveNonBlockingStrategyConfig_with_AIMD_when_aimd_called() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced().adaptiveNonBlocking(a -> a.aimd(x -> x.initialLimit(11)));

            AdaptiveNonBlockingStrategyConfig nb =
                    (AdaptiveNonBlockingStrategyConfig) snapshotFor(b).strategy();
            AimdLimitAlgorithmConfig aimd =
                    (AimdLimitAlgorithmConfig) nb.algorithm();
            assertThat(aimd.initialLimit()).isEqualTo(11);
        }

        @Test
        void should_set_AdaptiveNonBlockingStrategyConfig_with_Vegas_when_vegas_called() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced().adaptiveNonBlocking(a -> a.vegas(v -> v.maxLimit(777)));

            AdaptiveNonBlockingStrategyConfig nb =
                    (AdaptiveNonBlockingStrategyConfig) snapshotFor(b).strategy();
            VegasLimitAlgorithmConfig vegas = (VegasLimitAlgorithmConfig) nb.algorithm();
            assertThat(vegas.maxLimit()).isEqualTo(777);
        }

        @Test
        void should_throw_when_neither_aimd_nor_vegas_is_called() {
            TestBuilder b = new TestBuilder("inventory");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> b.adaptiveNonBlocking(a -> { }))
                    .withMessageContaining("adaptiveNonBlocking");
        }
    }

    @Nested
    @DisplayName("last-writer-wins between strategies")
    class LastWriterWins {

        @Test
        void semaphore_after_codel_should_win() {
            // What is to be tested: a later semaphore() call overwrites a previously-chosen
            // codel(...). Confirms that strategy choice obeys the same last-writer-wins rule
            // every other touch-based DSL setter follows.

            TestBuilder b = new TestBuilder("inventory");
            b.balanced()
                    .codel(c -> c.targetDelay(Duration.ofMillis(50))
                            .interval(Duration.ofMillis(500)))
                    .semaphore();

            assertThat(snapshotFor(b).strategy()).isInstanceOf(SemaphoreStrategyConfig.class);
        }

        @Test
        void codel_after_adaptive_should_win() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced()
                    .adaptive(a -> a.aimd(x -> x.initialLimit(33)))
                    .codel(c -> c.targetDelay(Duration.ofMillis(50))
                            .interval(Duration.ofMillis(500)));

            assertThat(snapshotFor(b).strategy()).isInstanceOf(CoDelStrategyConfig.class);
        }

        @Test
        void adaptive_after_adaptiveNonBlocking_should_win() {
            TestBuilder b = new TestBuilder("inventory");
            b.balanced()
                    .adaptiveNonBlocking(a -> a.aimd(x -> x.initialLimit(7)))
                    .adaptive(a -> a.vegas(v -> v.initialLimit(11)));

            assertThat(snapshotFor(b).strategy()).isInstanceOf(AdaptiveStrategyConfig.class);
        }
    }

    @Nested
    @DisplayName("strategy setters are orthogonal to presets")
    class StrategyVsPresets {

        @Test
        void semaphore_after_balanced_should_not_engage_the_preset_guard() {
            // semaphore() doesn't engage the preset-then-customize guard, so a subsequent
            // preset call still works. Pinning the orthogonality between strategy and preset
            // discipline that the new setters were designed for.

            TestBuilder b = new TestBuilder("inventory");
            b.semaphore().balanced();

            // No exception. The patch carries balanced + SemaphoreStrategyConfig.
            BulkheadSnapshot result = snapshotFor(b);
            assertThat(result.derivedFromPreset()).isEqualTo("balanced");
            assertThat(result.strategy()).isInstanceOf(SemaphoreStrategyConfig.class);
        }
    }
}
