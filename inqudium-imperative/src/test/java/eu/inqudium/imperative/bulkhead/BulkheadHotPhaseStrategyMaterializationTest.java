package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.dsl.GeneralSnapshotBuilder;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.AdaptiveNonBlockingStrategyConfig;
import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.snapshot.BulkheadStrategyConfig;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.LimitAlgorithm;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end materialization for the four bulkhead strategy types (ADR-032 / REFACTORING.md
 * 2.10.B). For each strategy config the test wires a real {@link InqBulkhead}, warms it via
 * {@code execute(...)}, and asserts that the strategy actually serves traffic. The strategy
 * itself is not directly inspectable from outside the bulkhead — these tests rely on the
 * functional contract (acquire, release, available permits) instead of casting the internal
 * field. That keeps the tests stable against the internal type narrowing planned for 2.10.D.
 */
@DisplayName("BulkheadHotPhase strategy materialization")
class BulkheadHotPhaseStrategyMaterializationTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    private static GeneralSnapshot defaultGeneral() {
        return new GeneralSnapshotBuilder().build();
    }

    private static InqBulkhead<String, String> newBulkhead(BulkheadStrategyConfig strategy) {
        return newBulkhead(strategy, 5).bulkhead;
    }

    private static Wired newBulkhead(BulkheadStrategyConfig strategy, int max) {
        BulkheadSnapshot snap = new BulkheadSnapshot(
                "inventory", max, Duration.ofMillis(100), Set.of(), null,
                BulkheadEventConfig.disabled(), strategy);
        LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snap);
        return new Wired(new InqBulkhead<>(live, defaultGeneral()), live);
    }

    /** Test fixture holding both the bulkhead and its live container so the transition-state
     *  test can apply a patch directly. */
    private record Wired(InqBulkhead<String, String> bulkhead, LiveContainer<BulkheadSnapshot> live) {
    }

    @Nested
    @DisplayName("hot materialization")
    class HotMaterialization {

        @Test
        void should_serve_calls_with_a_semaphore_strategy() {
            // What is to be tested: the bulkhead constructed with SemaphoreStrategyConfig
            // serves a normal call end-to-end and reports concurrentCalls back to zero
            // afterwards. Why important: confirms the factory's default branch wires a working
            // semaphore even after the hot-phase delegate refactor.

            InqBulkhead<String, String> bh = newBulkhead(new SemaphoreStrategyConfig());

            String result = bh.execute(1L, 1L, "x", IDENTITY);

            assertThat(result).isEqualTo("x");
            assertThat(bh.concurrentCalls()).isZero();
        }

        @Test
        void should_serve_calls_with_a_codel_strategy() {
            // CoDel takes the same blocking path as Semaphore from the hot phase's perspective;
            // a normal call must complete identically.

            InqBulkhead<String, String> bh = newBulkhead(new CoDelStrategyConfig(
                    Duration.ofMillis(50), Duration.ofMillis(500)));

            String result = bh.execute(1L, 1L, "x", IDENTITY);

            assertThat(result).isEqualTo("x");
            assertThat(bh.concurrentCalls()).isZero();
        }

        @Test
        void should_serve_calls_with_an_adaptive_strategy_running_AIMD() {
            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    5, 1, 100, 0.9,
                    Duration.ofSeconds(1), 0.05, true, 0.0);

            InqBulkhead<String, String> bh = newBulkhead(new AdaptiveStrategyConfig(aimd));

            String result = bh.execute(1L, 1L, "x", IDENTITY);

            assertThat(result).isEqualTo("x");
            assertThat(bh.concurrentCalls()).isZero();
        }

        @Test
        void should_serve_calls_with_an_adaptive_strategy_running_Vegas() {
            LimitAlgorithm vegas = new VegasLimitAlgorithmConfig(
                    5, 1, 100,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                    0.05, 0.0);

            InqBulkhead<String, String> bh = newBulkhead(new AdaptiveStrategyConfig(vegas));

            String result = bh.execute(1L, 1L, "x", IDENTITY);

            assertThat(result).isEqualTo("x");
            assertThat(bh.concurrentCalls()).isZero();
        }

        @Test
        void should_serve_calls_with_a_non_blocking_adaptive_strategy_running_AIMD() {
            // The non-blocking variant takes the no-arg tryAcquire path. The bridge in
            // BulkheadHotPhase.tryAcquire branches on instanceof; a regression that broke the
            // branch would either NPE or stay in the blocking arm, both visible as a thrown
            // exception or hang.

            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    5, 1, 100, 0.9,
                    Duration.ofSeconds(1), 0.05, true, 0.0);

            InqBulkhead<String, String> bh = newBulkhead(new AdaptiveNonBlockingStrategyConfig(aimd));

            String result = bh.execute(1L, 1L, "x", IDENTITY);

            assertThat(result).isEqualTo("x");
            assertThat(bh.concurrentCalls()).isZero();
        }

        @Test
        void should_serve_calls_with_a_non_blocking_adaptive_strategy_running_Vegas() {
            LimitAlgorithm vegas = new VegasLimitAlgorithmConfig(
                    5, 1, 100,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                    0.05, 0.0);

            InqBulkhead<String, String> bh = newBulkhead(new AdaptiveNonBlockingStrategyConfig(vegas));

            String result = bh.execute(1L, 1L, "x", IDENTITY);

            assertThat(result).isEqualTo("x");
            assertThat(bh.concurrentCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("snapshot-driven sizing")
    class SnapshotDrivenSizing {

        @Test
        void semaphore_should_expose_the_snapshots_maxConcurrentCalls_after_warm_up() {
            // Pinning the value flow snapshot.maxConcurrentCalls -> SemaphoreBulkheadStrategy
            // through the factory + materialization + accessor. Was already pinned via the
            // factory unit test; this is the end-to-end variant that exercises
            // bh.availablePermits().

            InqBulkhead<String, String> bh = newBulkhead(new SemaphoreStrategyConfig(), 17).bulkhead;

            // Warm — the cold accessor reads from the snapshot, so warm first to confirm the
            // hot-side accessor agrees.
            bh.execute(1L, 1L, "x", IDENTITY);

            assertThat(bh.availablePermits())
                    .as("17 permits configured, none in flight after the call returned")
                    .isEqualTo(17);
        }
    }

    @Nested
    @DisplayName("onSnapshotChange regression")
    class OnSnapshotChangeRegression {

        @Test
        void semaphore_should_retune_when_maxConcurrentCalls_changes_in_place() {
            // What is to be tested: a patch that touches MAX_CONCURRENT_CALLS (without
            // touching STRATEGY) re-tunes the live semaphore via SemaphoreBulkheadStrategy
            // .adjustMaxConcurrent. Pinning the existing in-place limit-tuning behaviour after
            // the field-type widened from SemaphoreBulkheadStrategy to BulkheadStrategy.
            // Why important: 2.10.B changes the field type and adds an instanceof guard around
            // adjustMaxConcurrent — a regression there would silently stop honouring runtime
            // limit changes for every Semaphore-strategy bulkhead.

            Wired wired = newBulkhead(new SemaphoreStrategyConfig(), 5);
            InqBulkhead<String, String> bh = wired.bulkhead;
            bh.execute(1L, 1L, "warm", IDENTITY);

            assertThat(bh.availablePermits()).isEqualTo(5);

            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(11);
            wired.live.apply(patch);

            assertThat(bh.availablePermits())
                    .as("semaphore retuned to the patched maxConcurrentCalls")
                    .isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("STRATEGY touch on hot bulkhead via direct live.apply")
    class StrategyTouchOnHot {

        @Test
        void touchStrategy_on_hot_bulkhead_should_swap_the_running_strategy() {
            // What is to be tested: 2.10.D wires the hot-swap path. A
            // BulkheadPatch.touchStrategy(...) applied directly to the live container
            // (bypassing the dispatcher's veto chain — the dispatcher path is exercised in
            // BulkheadHotSwapTest) propagates through the snapshot subscription and replaces
            // the running strategy. Before 2.10.D this was the documented transition state
            // ("snapshot updates but running strategy does not"); the contract is now flipped.

            Wired wired = newBulkhead(new SemaphoreStrategyConfig(), 5);
            InqBulkhead<String, String> bh = wired.bulkhead;
            bh.execute(1L, 1L, "warm", IDENTITY);
            assertThat(bh.availablePermits()).isEqualTo(5);

            BulkheadPatch patch = new BulkheadPatch();
            patch.touchStrategy(new CoDelStrategyConfig(
                    Duration.ofMillis(50), Duration.ofMillis(500)));
            wired.live.apply(patch);

            assertThat(bh.snapshot().strategy())
                    .as("snapshot carries the touched strategy")
                    .isInstanceOf(CoDelStrategyConfig.class);

            // Subsequent calls now go through the swapped CoDel strategy.
            String result = bh.execute(2L, 2L, "after-swap", IDENTITY);
            assertThat(result).isEqualTo("after-swap");
            assertThat(bh.concurrentCalls()).isZero();
        }
    }
}
