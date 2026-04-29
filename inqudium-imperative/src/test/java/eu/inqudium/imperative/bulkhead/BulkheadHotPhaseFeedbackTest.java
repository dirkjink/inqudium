package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.dsl.GeneralSnapshotBuilder;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.AdaptiveNonBlockingStrategyConfig;
import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.BulkheadStrategyConfig;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.LimitAlgorithm;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pinning tests for REFACTORING.md 2.13 — adaptive feedback wiring on
 * {@link BulkheadHotPhase#execute}. Each nested class targets one of the four 2.13 fixes:
 *
 * <ul>
 *   <li><b>AdaptiveAlgorithmFeedback</b> — drives a real adaptive AIMD bulkhead through enough
 *       calls that the algorithm raises its limit. Without the {@code onCallComplete} call the
 *       bulkhead's accessor would stay at the algorithm's {@code initialLimit} forever.</li>
 *   <li><b>OnCallCompleteOrdering</b> — uses a recording stub strategy to pin that
 *       {@code onCallComplete} runs before {@code release} (ADR-020 ordering).</li>
 *   <li><b>AlgorithmFailureIsolation</b> — uses a stub strategy whose {@code onCallComplete}
 *       throws to verify the throw is logged and swallowed, and the permit is still released.</li>
 *   <li><b>ExceptionOptimizationFlag</b> — pins that the {@code enableExceptionOptimization}
 *       flag on {@link GeneralSnapshot} flows through to
 *       {@link InqBulkheadFullException}: when on, no stack frames; when off, frames present.</li>
 *   <li><b>ColdPhaseAccessorForAdaptive</b> — pins that {@code availablePermits()} on a cold
 *       adaptive bulkhead reports the algorithm's {@code initialLimit}, not the snapshot's
 *       {@code maxConcurrentCalls}.</li>
 * </ul>
 */
@DisplayName("BulkheadHotPhase adaptive feedback (2.13)")
class BulkheadHotPhaseFeedbackTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    private static GeneralSnapshot defaultGeneral() {
        return new GeneralSnapshotBuilder().build();
    }

    private static GeneralSnapshot generalWithOptimization(boolean enable) {
        return new GeneralSnapshotBuilder()
                .enableExceptionOptimization(enable)
                .build();
    }

    private static BulkheadSnapshot snapshotOf(BulkheadStrategyConfig strategy, int max) {
        return new BulkheadSnapshot(
                "feedback", max, Duration.ofMillis(100), Set.of(), null,
                BulkheadEventConfig.disabled(), strategy);
    }

    private static InqBulkhead newBulkhead(BulkheadStrategyConfig strategy, int max) {
        LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshotOf(strategy, max));
        return new InqBulkhead(live, defaultGeneral());
    }

    @Nested
    @DisplayName("adaptive algorithm feedback")
    class AdaptiveAlgorithmFeedback {

        @Test
        void should_grow_the_AIMD_limit_through_repeated_successful_calls_blocking() {
            // What is to be tested: that BulkheadHotPhase.execute calls onCallComplete on the
            // adaptive strategy, allowing AIMD to raise its limit. With initialLimit=1 and a
            // minUtilizationThreshold of 1.0, a successful call grows the limit only when the
            // algorithm sees the in-flight count it actually served — i.e. only when
            // onCallComplete runs *before* release. If the order were reversed the threshold
            // would not be met (activeCalls=0 < limit*1.0) and the limit would stay at 1.
            // Why successful: after a single call, availablePermits() reports the limit that
            // grew from 1 to 2 because the threshold check saw 1 in-flight call.
            // Why important: this is the central regression the 2.13 fix exists to prevent —
            // adaptive bulkheads silently degraded to static limiters at their initialLimit.

            // Given — AIMD with the smallest possible limit so a single call exercises growth
            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    1, 1, 100, 0.5,
                    Duration.ofMillis(1), 0.5, false, 1.0);
            InqBulkhead bh = newBulkhead(new AdaptiveStrategyConfig(aimd), 1);

            // When
            bh.execute(1L, 1L, "x", IDENTITY);

            // Then — limit grew from 1 to 2; permit is back so available = 2
            assertThat(bh.availablePermits())
                    .as("AIMD raised its limit from 1 to 2 after seeing the in-flight call")
                    .isEqualTo(2);
        }

        @Test
        void should_grow_the_AIMD_limit_through_repeated_successful_calls_non_blocking() {
            // Same contract for the non-blocking adaptive variant. The bulkhead must call
            // onCallComplete on AdaptiveNonBlockingBulkheadStrategy too — both adaptive
            // strategies sit behind the BulkheadStrategy interface and the hot-phase code path
            // is shared.

            // Given
            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    1, 1, 100, 0.5,
                    Duration.ofMillis(1), 0.5, false, 1.0);
            InqBulkhead bh = newBulkhead(new AdaptiveNonBlockingStrategyConfig(aimd), 1);

            // When
            bh.execute(1L, 1L, "x", IDENTITY);

            // Then
            assertThat(bh.availablePermits())
                    .as("non-blocking AIMD raised its limit from 1 to 2")
                    .isEqualTo(2);
        }

        @Test
        void semaphore_remains_unaffected_by_the_feedback_call() {
            // Defence-in-depth: onCallComplete is a no-op on the static SemaphoreBulkheadStrategy
            // (the default impl on BulkheadStrategy). A regression that wired onCallComplete to
            // do something stateful would silently corrupt non-adaptive bulkheads. This test
            // confirms a semaphore bulkhead's permit pool is unchanged after calls run.

            // Given
            InqBulkhead bh = newBulkhead(new SemaphoreStrategyConfig(), 7);

            // When
            for (int i = 0; i < 10; i++) {
                bh.execute(1L, i, "x", IDENTITY);
            }

            // Then
            assertThat(bh.availablePermits()).isEqualTo(7);
            assertThat(bh.concurrentCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("onCallComplete-before-release ordering")
    class OnCallCompleteOrdering {

        @Test
        void should_call_onCallComplete_before_release() {
            // What is to be tested: BulkheadHotPhase.execute must invoke
            // strategy.onCallComplete *before* strategy.release on the success path. ADR-020
            // documents this ordering as required for adaptive algorithms — they read the
            // in-flight count and would see an artificially low value if release ran first.
            // Why successful: a stub strategy records the order of calls; after one execute
            // the recorded sequence has onCallComplete at index 0 and release at index 1.
            // Why important: ordering is a contract that's hard to detect from outside without
            // a stub — once broken, adaptive bulkheads behave as if no in-flight work exists.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(
                    snapshotOf(new SemaphoreStrategyConfig(), 1));
            InqBulkhead bh = new InqBulkhead(live, defaultGeneral());
            RecordingStrategy stub = new RecordingStrategy();
            BulkheadHotPhase phase = new BulkheadHotPhase(bh, stub);

            // When — drive the hot phase directly with the stub strategy
            phase.execute(1L, 1L, "x", IDENTITY);

            // Then
            assertThat(stub.events)
                    .as("onCallComplete must run before release on the success path")
                    .containsExactly("onCallComplete:success", "release");
        }

        @Test
        void should_call_onCallComplete_with_failure_flag_when_chain_throws() {
            // Companion test: on the failure path, onCallComplete must still run, with the
            // success flag set to false.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(
                    snapshotOf(new SemaphoreStrategyConfig(), 1));
            InqBulkhead bh = new InqBulkhead(live, defaultGeneral());
            RecordingStrategy stub = new RecordingStrategy();
            BulkheadHotPhase phase = new BulkheadHotPhase(bh, stub);
            InternalExecutor<String, String> failing = (chainId, callId, arg) -> {
                throw new RuntimeException("boom");
            };

            // When
            assertThatThrownBy(() -> phase.execute(1L, 1L, "x", failing))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("boom");

            // Then
            assertThat(stub.events)
                    .as("onCallComplete still runs on the failure path, with isSuccess=false")
                    .containsExactly("onCallComplete:failure", "release");
        }
    }

    @Nested
    @DisplayName("algorithm-failure isolation")
    class AlgorithmFailureIsolation {

        @Test
        void should_swallow_an_onCallComplete_throw_and_still_release() {
            // What is to be tested: a throw inside strategy.onCallComplete must not propagate
            // to the caller, and must not block strategy.release. The legacy
            // ImperativeBulkhead.releaseAndReport pattern is the model — log the algorithm
            // failure and continue with release.
            // Why successful: the bulkhead returns the chain's normal result, the stub records
            // a release call, and no exception escapes execute.
            // Why important: a permit leak is far worse than a missed limit-update sample. The
            // bulkhead's guarantee is that release runs in a finally; the new feedback wiring
            // must not weaken that guarantee.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(
                    snapshotOf(new SemaphoreStrategyConfig(), 1));
            InqBulkhead bh = new InqBulkhead(live, defaultGeneral());
            ThrowingStrategy stub = new ThrowingStrategy();
            BulkheadHotPhase phase = new BulkheadHotPhase(bh, stub);

            // When
            String result = phase.execute(1L, 1L, "x", IDENTITY);

            // Then
            assertThat(result).isEqualTo("x");
            assertThat(stub.released)
                    .as("release ran despite the algorithm failure")
                    .isTrue();
            assertThat(stub.onCallCompleteInvocations)
                    .as("onCallComplete was attempted exactly once")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("exception-optimization flag")
    class ExceptionOptimizationFlag {

        @Test
        void should_enable_suppression_when_optimization_is_enabled() {
            // What is to be tested: when GeneralSnapshot.enableExceptionOptimization is true,
            // the InqBulkheadFullException thrown on the rejection path is constructed via
            // {@code Throwable(message, cause, enableSuppression=true, writableStackTrace=false)}.
            // {@link InqBulkheadFullException} overrides {@code fillInStackTrace} to always be a
            // no-op (ADR-020), so the stack trace is empty regardless of the flag — the
            // observable difference is on the suppression chain. With suppression enabled,
            // {@code addSuppressed} stores the supplied exception and {@code getSuppressed}
            // returns it; with suppression disabled, both are no-ops.
            // Why successful: after addSuppressed, getSuppressed contains exactly the secondary.
            // Why important: pinning the flag's wiring through to the constructor — without
            // the fix, the hardcoded {@code false} would mean addSuppressed is always a no-op.

            // Given — fail-fast bulkhead with one permit, occupied
            InqBulkheadFullException thrown = forceRejection(
                    generalWithOptimization(true));

            // When
            RuntimeException secondary = new RuntimeException("secondary");
            thrown.addSuppressed(secondary);

            // Then
            assertThat(thrown.getSuppressed())
                    .as("optimization=true keeps suppression on; addSuppressed retains the entry")
                    .containsExactly(secondary);
        }

        @Test
        void should_disable_suppression_when_optimization_is_disabled() {
            // Companion: when the flag is false, the InqException constructor passes
            // {@code enableSuppression=false}; addSuppressed becomes a no-op and getSuppressed
            // returns an empty array.

            // Given
            InqBulkheadFullException thrown = forceRejection(
                    generalWithOptimization(false));

            // When
            thrown.addSuppressed(new RuntimeException("secondary"));

            // Then
            assertThat(thrown.getSuppressed())
                    .as("optimization=false disables suppression; addSuppressed dropped the entry")
                    .isEmpty();
        }

        @Test
        void should_omit_stack_frames_on_interrupted_acquire_when_optimization_is_enabled() {
            // Cross-check on InqBulkheadInterruptedException, which does not override
            // fillInStackTrace. Here the flag's effect on writableStackTrace is observable
            // directly: optimization=true means writableStackTrace=false, the stack trace is
            // never filled, getStackTrace() is empty.

            // Given — interrupt a thread waiting on a long acquire
            BulkheadSnapshot snap = new BulkheadSnapshot(
                    "feedback", 1, Duration.ofSeconds(5), Set.of(), null,
                    BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snap);
            InqBulkhead bh = new InqBulkhead(live, generalWithOptimization(true));
            HoldingExecutor holder = new HoldingExecutor();
            Thread first = Thread.startVirtualThread(
                    () -> bh.execute(1L, 1L, "first", holder));
            holder.awaitAcquire();

            try {
                Throwable[] captured = new Throwable[1];
                Thread waiter = new Thread(() -> {
                    try {
                        bh.execute(1L, 2L, "second", IDENTITY);
                    } catch (Throwable t) {
                        captured[0] = t;
                    }
                });
                waiter.start();
                // Give the waiter a moment to enter tryAcquire's blocking wait, then interrupt.
                Thread.sleep(50);
                waiter.interrupt();
                waiter.join(2000);

                // Then
                assertThat(captured[0])
                        .isInstanceOf(eu.inqudium.core.element.bulkhead
                                .InqBulkheadInterruptedException.class);
                assertThat(captured[0].getStackTrace())
                        .as("optimization=true makes writableStackTrace=false — empty trace")
                        .isEmpty();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            } finally {
                holder.release();
                joinUninterruptibly(first);
            }
        }

        private InqBulkheadFullException forceRejection(GeneralSnapshot general) {
            BulkheadSnapshot snap = new BulkheadSnapshot(
                    "feedback", 1, Duration.ZERO, Set.of(), null,
                    BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snap);
            InqBulkhead bh = new InqBulkhead(live, general);
            HoldingExecutor holder = new HoldingExecutor();
            Thread first = Thread.startVirtualThread(
                    () -> bh.execute(1L, 1L, "first", holder));
            holder.awaitAcquire();
            try {
                Throwable rejection = catchOf(() ->
                        bh.execute(1L, 2L, "second", IDENTITY));
                assertThat(rejection).isInstanceOf(InqBulkheadFullException.class);
                return (InqBulkheadFullException) rejection;
            } finally {
                holder.release();
                joinUninterruptibly(first);
            }
        }
    }

    @Nested
    @DisplayName("cold-phase accessor for adaptive variants")
    class ColdPhaseAccessorForAdaptive {

        @Test
        void semaphore_cold_should_report_snapshot_maxConcurrentCalls() {
            // Given
            InqBulkhead bh = newBulkhead(new SemaphoreStrategyConfig(), 13);

            // When / Then — never warmed; cold accessor honours the snapshot
            assertThat(bh.availablePermits()).isEqualTo(13);
        }

        @Test
        void adaptive_blocking_AIMD_cold_should_report_algorithm_initialLimit() {
            // What is to be tested: the cold accessor on an adaptive AIMD bulkhead reports the
            // algorithm's initialLimit, not snapshot.maxConcurrentCalls — the latter is what
            // the factory ignores for adaptive strategies.
            // Why successful: snapshot says 50, AIMD initial=20, cold accessor reports 20.
            // Why important: pinning the cross-strategy continuity contract — observers that
            // poll availablePermits before warm-up must see the same number the bulkhead
            // reports immediately after warm-up.

            // Given
            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    20, 1, 100, 0.5,
                    Duration.ofSeconds(1), 0.5, true, 0.0);
            InqBulkhead bh = newBulkhead(new AdaptiveStrategyConfig(aimd), 50);

            // When / Then
            assertThat(bh.availablePermits())
                    .as("cold adaptive AIMD reports the algorithm's initialLimit")
                    .isEqualTo(20);
        }

        @Test
        void adaptive_blocking_Vegas_cold_should_report_algorithm_initialLimit() {
            // Given
            LimitAlgorithm vegas = new VegasLimitAlgorithmConfig(
                    8, 1, 100,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                    0.05, 0.0);
            InqBulkhead bh = newBulkhead(new AdaptiveStrategyConfig(vegas), 50);

            // When / Then
            assertThat(bh.availablePermits()).isEqualTo(8);
        }

        @Test
        void adaptive_non_blocking_AIMD_cold_should_report_algorithm_initialLimit() {
            // Given
            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    11, 1, 100, 0.5,
                    Duration.ofSeconds(1), 0.5, true, 0.0);
            InqBulkhead bh = newBulkhead(new AdaptiveNonBlockingStrategyConfig(aimd), 50);

            // When / Then
            assertThat(bh.availablePermits()).isEqualTo(11);
        }

        @Test
        void adaptive_non_blocking_Vegas_cold_should_report_algorithm_initialLimit() {
            // Given
            LimitAlgorithm vegas = new VegasLimitAlgorithmConfig(
                    9, 1, 100,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                    0.05, 0.0);
            InqBulkhead bh = newBulkhead(new AdaptiveNonBlockingStrategyConfig(vegas), 50);

            // When / Then
            assertThat(bh.availablePermits()).isEqualTo(9);
        }

        @Test
        void cold_concurrentCalls_should_be_zero_for_every_strategy() {
            // Defence-in-depth: cold concurrentCalls is always zero — no permit can be in
            // flight before the strategy exists. This holds for every strategy variant; the
            // accessor short-circuits without consulting snapshot or algorithm.

            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    20, 1, 100, 0.5,
                    Duration.ofSeconds(1), 0.5, true, 0.0);

            assertThat(newBulkhead(new SemaphoreStrategyConfig(), 5).concurrentCalls()).isZero();
            assertThat(newBulkhead(new AdaptiveStrategyConfig(aimd), 5).concurrentCalls()).isZero();
            assertThat(newBulkhead(new AdaptiveNonBlockingStrategyConfig(aimd), 5).concurrentCalls())
                    .isZero();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Test stubs
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Records the order in which {@link #onCallComplete} and {@link #release} are invoked.
     * Used to pin the ADR-020 ordering contract on the bulkhead's execute path.
     */
    private static final class RecordingStrategy implements BlockingBulkheadStrategy {

        final List<String> events = new ArrayList<>();
        private int active;

        @Override
        public RejectionContext tryAcquire(Duration timeout) {
            active++;
            return null;
        }

        @Override
        public void release() {
            events.add("release");
            active--;
        }

        @Override
        public void rollback() {
            release();
        }

        @Override
        public void onCallComplete(long rttNanos, boolean isSuccess) {
            events.add("onCallComplete:" + (isSuccess ? "success" : "failure"));
        }

        @Override
        public int availablePermits() {
            return Integer.MAX_VALUE - active;
        }

        @Override
        public int concurrentCalls() {
            return active;
        }

        @Override
        public int maxConcurrentCalls() {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Strategy whose {@link #onCallComplete} throws. Used to verify the bulkhead logs and
     * swallows the algorithm failure and still calls {@link #release}.
     */
    private static final class ThrowingStrategy implements BlockingBulkheadStrategy {

        int onCallCompleteInvocations;
        boolean released;

        @Override
        public RejectionContext tryAcquire(Duration timeout) {
            return null;
        }

        @Override
        public void release() {
            released = true;
        }

        @Override
        public void rollback() {
            release();
        }

        @Override
        public void onCallComplete(long rttNanos, boolean isSuccess) {
            onCallCompleteInvocations++;
            throw new RuntimeException("simulated algorithm failure");
        }

        @Override
        public int availablePermits() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int concurrentCalls() {
            return 0;
        }

        @Override
        public int maxConcurrentCalls() {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Holding executor: signals when its permit is acquired and waits for an external release
     * before returning. Used by the rejection-path tests to pin the rejecting branch
     * deterministically without relying on virtual-thread ordering races.
     */
    private static final class HoldingExecutor implements InternalExecutor<String, String> {

        private final java.util.concurrent.CountDownLatch acquired =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

        @Override
        public String execute(long chainId, long callId, String argument) {
            acquired.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test hung waiting for release latch");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return argument;
        }

        void awaitAcquire() {
            try {
                if (!acquired.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test hung waiting for acquire latch");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
        }

        void release() {
            release.countDown();
        }
    }

    private static Throwable catchOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static void joinUninterruptibly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
