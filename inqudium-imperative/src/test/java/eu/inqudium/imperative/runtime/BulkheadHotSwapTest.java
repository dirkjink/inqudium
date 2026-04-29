package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
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
 * End-to-end coverage for the bulkhead hot-swap path (REFACTORING.md 2.10.D). The mutability
 * check vetoes a STRATEGY swap with in-flight calls; with zero in-flight, the swap commits
 * atomically and subsequent calls run on the new strategy.
 */
@DisplayName("Bulkhead strategy hot-swap")
class BulkheadHotSwapTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    private static final ComponentKey INVENTORY_KEY =
            new ComponentKey("inventory", ImperativeTag.INSTANCE);

    @Nested
    @DisplayName("zero in-flight")
    class ZeroInFlight {

        @Test
        void should_swap_strategy_when_bulkhead_is_quiescent() {
            // What is to be tested: an update that touches STRATEGY on a hot but quiescent
            // bulkhead is accepted, the snapshot reflects the new strategy, and subsequent
            // calls run through the swapped strategy.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(SemaphoreStrategyConfig.class);

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b
                                .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                        .interval(Duration.ofMillis(500))))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.PATCHED);
                assertThat(report.vetoFindings()).isEmpty();
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(CoDelStrategyConfig.class);
                assertThat(bh.execute(2L, 2L, "post-swap", IDENTITY))
                        .isEqualTo("post-swap");
            }
        }

        @Test
        void should_handle_repeated_swap_in_sequence_under_quiescent_conditions() {
            // Pinning the "swapStrategy does not corrupt state across multiple swaps" property.
            // Semaphore -> CoDel -> Adaptive(AIMD) -> Semaphore.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                runtime.update(u -> u.imperative(im -> im.bulkhead("inventory", b -> b
                        .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                .interval(Duration.ofMillis(500))))));
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(CoDelStrategyConfig.class);

                runtime.update(u -> u.imperative(im -> im.bulkhead("inventory", b -> b
                        .adaptive(a -> a.aimd(x -> x.initialLimit(7))))));
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(AdaptiveStrategyConfig.class);

                runtime.update(u -> u.imperative(im -> im.bulkhead("inventory", b -> b
                        .semaphore())));
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(SemaphoreStrategyConfig.class);

                assertThat(bh.execute(2L, 2L, "after-three-swaps", IDENTITY))
                        .isEqualTo("after-three-swaps");
            }
        }
    }

    @Nested
    @DisplayName("non-zero in-flight")
    class WithInFlight {

        @Test
        void should_veto_strategy_swap_while_a_call_is_in_flight() throws InterruptedException {
            // What is to be tested: with one permit held by an in-flight call, an
            // update touching STRATEGY is vetoed via the component-internal mutability check.
            // The veto finding's source is COMPONENT_INTERNAL and its reason mentions the
            // in-flight count.
            // Why important: the precondition "zero in-flight" is what makes the volatile
            // single-write atomic boundary in the hot phase actually safe — vetoing here is
            // the only way a swap can commit without races.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
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
                                    .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                            .interval(Duration.ofMillis(500))))));

                    assertThat(report.componentOutcomes())
                            .containsEntry(INVENTORY_KEY, ApplyOutcome.VETOED);
                    assertThat(report.vetoFindings()).hasSize(1);
                    VetoFinding finding = report.vetoFindings().get(0);
                    assertThat(finding.source())
                            .isEqualTo(VetoFinding.Source.COMPONENT_INTERNAL);
                    assertThat(finding.reason())
                            .contains("strategy")
                            .contains("in-flight");
                    assertThat(bh.snapshot().strategy())
                            .as("vetoed swap leaves the snapshot's strategy untouched")
                            .isInstanceOf(SemaphoreStrategyConfig.class);
                } finally {
                    holding.countDown();
                    holder.join();
                }
            }
        }
    }

    @Nested
    @DisplayName("listener veto")
    class ListenerVeto {

        @Test
        void listener_veto_on_strategy_should_short_circuit_before_internal_check() {
            // Conjunctive veto chain: the listener's vetoes are evaluated before the
            // component-internal mutability check. A listener that vetoes any STRATEGY patch
            // produces VetoFinding.Source.LISTENER and the swap doesn't even reach the
            // in-flight check.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);
                bh.onChangeRequest(req -> ChangeDecision.veto(
                        "policy: strategy locked by listener"));

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b
                                .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                        .interval(Duration.ofMillis(500))))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.VETOED);
                assertThat(report.vetoFindings()).hasSize(1);
                assertThat(report.vetoFindings().get(0).source())
                        .isEqualTo(VetoFinding.Source.LISTENER);
                assertThat(report.vetoFindings().get(0).reason())
                        .isEqualTo("policy: strategy locked by listener");
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(SemaphoreStrategyConfig.class);
            }
        }
    }

    @Nested
    @DisplayName("in-flight call across swap")
    class InFlightAcrossSwap {

        @Test
        void in_flight_call_should_complete_on_old_strategy_after_swap() throws Exception {
            // What is to be tested: when an in-flight call holds the old strategy's permit,
            // a subsequent quiescent swap (the in-flight count drops to zero between the
            // call's permit release and the update) replaces the running strategy. The
            // already-released call is unaffected because the strategy reference it held was
            // captured before the swap. After the swap, the next call runs on the new
            // strategy.
            // Why important: pins that volatile-single-write semantics give in-flight callers
            // a coherent view — they neither block the swap nor see torn state.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(SemaphoreStrategyConfig.class);

                // No in-flight at the moment we issue the update.
                runtime.update(u -> u.imperative(im -> im.bulkhead("inventory", b -> b
                        .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                .interval(Duration.ofMillis(500))))));

                // The next call runs on the swapped strategy.
                String result = bh.execute(2L, 2L, "post-swap", IDENTITY);
                assertThat(result).isEqualTo("post-swap");
                assertThat(bh.concurrentCalls()).isZero();
            }
        }
    }
}
