package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the generalized live-tunability check (REFACTORING.md 2.10.D). The
 * BulkheadHotPhase mutability check evaluates against the post-patch snapshot:
 * MAX_CONCURRENT_CALLS is live-tunable on Semaphore and only on Semaphore. Combined patches
 * that swap to Semaphore <em>and</em> tune the limit pass; isolated MAX_CONCURRENT_CALLS
 * patches on non-Semaphore strategies are vetoed with a clear reason.
 */
@DisplayName("Bulkhead live-tunability check")
class BulkheadLiveTunabilityTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    private static final ComponentKey INVENTORY_KEY =
            new ComponentKey("inventory", ImperativeTag.INSTANCE);

    @Nested
    @DisplayName("isolated MAX_CONCURRENT_CALLS patch")
    class IsolatedMaxConcurrentPatch {

        @Test
        void should_accept_on_hot_semaphore_bulkhead() {
            // Regression pin for the existing in-place tune behaviour. Semaphore is the only
            // strategy where MAX_CONCURRENT_CALLS is live-tunable; the existing dispatcher
            // path must keep working.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.PATCHED);
                assertThat(bh.availablePermits()).isEqualTo(99);
            }
        }

        @Test
        void should_veto_on_hot_codel_bulkhead() {
            // What is to be tested: a MAX_CONCURRENT_CALLS-only patch on a hot CoDel bulkhead
            // is vetoed with COMPONENT_INTERNAL source and a reason mentioning the
            // non-tunable strategy.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                    .interval(Duration.ofMillis(500)))))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.VETOED);
                assertThat(report.vetoFindings()).hasSize(1);
                VetoFinding f = report.vetoFindings().get(0);
                assertThat(f.source()).isEqualTo(VetoFinding.Source.COMPONENT_INTERNAL);
                assertThat(f.reason())
                        .contains("maxConcurrentCalls")
                        .contains("CoDelStrategyConfig");
            }
        }

        @Test
        void should_veto_on_hot_adaptive_bulkhead() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .adaptive(a -> a.aimd(x -> x.initialLimit(7)))))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.VETOED);
                assertThat(report.vetoFindings().get(0).reason())
                        .contains("AdaptiveStrategyConfig");
            }
        }
    }

    @Nested
    @DisplayName("combined STRATEGY + MAX_CONCURRENT_CALLS patch")
    class CombinedPatch {

        @Test
        void should_accept_when_post_patch_strategy_is_semaphore_and_no_in_flight() {
            // What is to be tested: a single update that swaps the strategy to Semaphore AND
            // sets a new MAX_CONCURRENT_CALLS is accepted, because evaluate() inspects the
            // post-patch snapshot — MAX_CONCURRENT_CALLS is live-tunable on the post-patch
            // semaphore strategy.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                    .interval(Duration.ofMillis(500)))))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b
                                .semaphore()
                                .maxConcurrentCalls(99))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.PATCHED);
                assertThat(report.vetoFindings()).isEmpty();
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(SemaphoreStrategyConfig.class);
                assertThat(bh.availablePermits()).isEqualTo(99);
            }
        }

        @Test
        void should_veto_for_strategy_when_combined_patch_runs_with_in_flight()
                throws InterruptedException {
            // What is to be tested: a combined patch on a bulkhead with in-flight work is
            // vetoed because the STRATEGY swap fails its zero-in-flight precondition. Even
            // though the post-patch strategy is Semaphore (which would normally allow the
            // MAX_CONCURRENT_CALLS change), the strategy-swap check fires first and produces
            // a single VetoFinding pointing at the strategy issue.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                    .interval(Duration.ofMillis(500)))))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                CountDownLatch holding = new CountDownLatch(1);
                CountDownLatch acquired = new CountDownLatch(1);
                InternalExecutor<String, String> blocking = (cid, callId, arg) -> {
                    acquired.countDown();
                    try {
                        holding.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return arg;
                };
                Thread holder = Thread.startVirtualThread(
                        () -> bh.execute(1L, 1L, "first", blocking));
                assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();

                try {
                    BuildReport report = runtime.update(u -> u.imperative(im -> im
                            .bulkhead("inventory", b -> b
                                    .semaphore()
                                    .maxConcurrentCalls(99))));

                    assertThat(report.componentOutcomes())
                            .containsEntry(INVENTORY_KEY, ApplyOutcome.VETOED);
                    assertThat(report.vetoFindings()).hasSize(1);
                    assertThat(report.vetoFindings().get(0).reason())
                            .as("strategy precondition is checked before the live-tunability "
                                    + "branch — the surfaced reason is the strategy swap")
                            .contains("strategy")
                            .contains("in-flight");
                    assertThat(bh.snapshot().strategy())
                            .isInstanceOf(CoDelStrategyConfig.class);
                } finally {
                    holding.countDown();
                    holder.join();
                }
            }
        }
    }
}
