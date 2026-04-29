package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.AdaptiveNonBlockingStrategyConfig;
import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the four strategy setters on the imperative bulkhead DSL
 * (REFACTORING.md 2.10.C). Walks the full path: {@code Inqudium.configure().imperative(...)}
 * through to the live snapshot, plus the same path on {@code runtime.update(...)} to confirm
 * the update flow honours strategy patches.
 */
@DisplayName("Bulkhead strategy DSL end-to-end")
class BulkheadStrategyDslEndToEndTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    @Nested
    @DisplayName("initial build")
    class InitialBuild {

        @Test
        void should_default_to_SemaphoreStrategyConfig_without_an_explicit_strategy_setter() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                BulkheadSnapshot snap =
                        runtime.imperative().bulkhead("inventory").snapshot();
                assertThat(snap.strategy()).isInstanceOf(SemaphoreStrategyConfig.class);
            }
        }

        @Test
        void should_carry_codel_through_to_the_snapshot() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .codel(c -> c
                                    .targetDelay(Duration.ofMillis(75))
                                    .interval(Duration.ofMillis(750)))))
                    .build()) {

                CoDelStrategyConfig codel = (CoDelStrategyConfig)
                        runtime.imperative().bulkhead("inventory").snapshot().strategy();
                assertThat(codel.targetDelay()).isEqualTo(Duration.ofMillis(75));
                assertThat(codel.interval()).isEqualTo(Duration.ofMillis(750));
            }
        }

        @Test
        void should_carry_adaptive_with_AIMD_through_to_the_snapshot() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .adaptive(a -> a.aimd(x -> x.initialLimit(33)))))
                    .build()) {

                AdaptiveStrategyConfig adaptive = (AdaptiveStrategyConfig)
                        runtime.imperative().bulkhead("inventory").snapshot().strategy();
                AimdLimitAlgorithmConfig aimd =
                        (AimdLimitAlgorithmConfig) adaptive.algorithm();
                assertThat(aimd.initialLimit()).isEqualTo(33);
            }
        }

        @Test
        void should_carry_adaptiveNonBlocking_with_Vegas_through_to_the_snapshot() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .adaptiveNonBlocking(a -> a.vegas(v -> v
                                    .initialLimit(11)))))
                    .build()) {

                AdaptiveNonBlockingStrategyConfig nb = (AdaptiveNonBlockingStrategyConfig)
                        runtime.imperative().bulkhead("inventory").snapshot().strategy();
                VegasLimitAlgorithmConfig vegas = (VegasLimitAlgorithmConfig) nb.algorithm();
                assertThat(vegas.initialLimit()).isEqualTo(11);
            }
        }

        @Test
        void should_serve_a_call_for_a_codel_strategy_built_through_the_dsl() {
            // Smoke test for the full path: DSL → factory → hot phase. Was already pinned in
            // the materialization test for direct snapshot construction; this is the variant
            // that exercises the user-facing builder.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                    .interval(Duration.ofMillis(500)))))
                    .build()) {

                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                String result = bh.execute(1L, 1L, "x", IDENTITY);

                assertThat(result).isEqualTo("x");
                assertThat(bh.concurrentCalls()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("runtime.update")
    class RuntimeUpdate {

        @Test
        void should_patch_the_strategy_field_via_the_dsl_and_report_PATCHED() {
            // What is to be tested: a runtime.update that uses the new DSL strategy setter
            // produces a PATCHED outcome, the snapshot's strategy field reflects the new
            // value, and (after 2.10.D) the running strategy is hot-swapped. The bulkhead is
            // hot but quiescent (no in-flight calls) at update time, so the mutability
            // check accepts.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b
                                .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                        .interval(Duration.ofMillis(500))))));

                assertThat(report.componentOutcomes())
                        .containsEntry(
                                new ComponentKey("inventory", ImperativeTag.INSTANCE),
                                ApplyOutcome.PATCHED);
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(CoDelStrategyConfig.class);
                // After 2.10.D the running strategy is also swapped — the bulkhead now
                // serves subsequent calls through the freshly materialized CoDel strategy.
                assertThat(bh.execute(2L, 2L, "post", IDENTITY)).isEqualTo("post");
            }
        }
    }
}
