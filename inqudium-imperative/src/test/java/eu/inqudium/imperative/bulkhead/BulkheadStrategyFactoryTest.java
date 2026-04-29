package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.dsl.GeneralSnapshotBuilder;
import eu.inqudium.config.snapshot.AdaptiveNonBlockingStrategyConfig;
import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.BulkheadStrategyConfig;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.LimitAlgorithm;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import eu.inqudium.core.element.bulkhead.strategy.AdaptiveNonBlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.imperative.bulkhead.strategy.AdaptiveBulkheadStrategy;
import eu.inqudium.imperative.bulkhead.strategy.CoDelBulkheadStrategy;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BulkheadStrategyFactory}. Hits the package-private factory directly
 * — the same package boundary the production hot phase uses — so each strategy variant can be
 * built and inspected in isolation from the bulkhead's lifecycle.
 */
@DisplayName("BulkheadStrategyFactory")
class BulkheadStrategyFactoryTest {

    private static GeneralSnapshot defaultGeneral() {
        return new GeneralSnapshotBuilder().build();
    }

    private static BulkheadSnapshot snapshotWith(BulkheadStrategyConfig strategy) {
        return new BulkheadSnapshot(
                "inventory", 10, Duration.ofMillis(100), Set.of(), null,
                BulkheadEventConfig.disabled(), strategy);
    }

    @Nested
    @DisplayName("strategy dispatch")
    class StrategyDispatch {

        @Test
        void should_build_SemaphoreBulkheadStrategy_for_SemaphoreStrategyConfig() {
            BulkheadStrategy result = BulkheadStrategyFactory.create(
                    snapshotWith(new SemaphoreStrategyConfig()), defaultGeneral());

            assertThat(result).isInstanceOf(SemaphoreBulkheadStrategy.class);
        }

        @Test
        void should_build_CoDelBulkheadStrategy_for_CoDelStrategyConfig() {
            BulkheadStrategy result = BulkheadStrategyFactory.create(
                    snapshotWith(new CoDelStrategyConfig(
                            Duration.ofMillis(50), Duration.ofMillis(500))),
                    defaultGeneral());

            assertThat(result).isInstanceOf(CoDelBulkheadStrategy.class);
        }

        @Test
        void should_build_AdaptiveBulkheadStrategy_for_AdaptiveStrategyConfig_with_AIMD() {
            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    20, 5, 100, 0.9,
                    Duration.ofSeconds(1), 0.05, true, 0.5);

            BulkheadStrategy result = BulkheadStrategyFactory.create(
                    snapshotWith(new AdaptiveStrategyConfig(aimd)), defaultGeneral());

            assertThat(result).isInstanceOf(AdaptiveBulkheadStrategy.class);
            assertThat(result.maxConcurrentCalls())
                    .as("the algorithm's initialLimit drives the adaptive strategy's view of "
                            + "maxConcurrentCalls — not the snapshot's static hint")
                    .isEqualTo(20);
        }

        @Test
        void should_build_AdaptiveBulkheadStrategy_for_AdaptiveStrategyConfig_with_Vegas() {
            LimitAlgorithm vegas = new VegasLimitAlgorithmConfig(
                    20, 5, 100,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                    0.05, 0.5);

            BulkheadStrategy result = BulkheadStrategyFactory.create(
                    snapshotWith(new AdaptiveStrategyConfig(vegas)), defaultGeneral());

            assertThat(result).isInstanceOf(AdaptiveBulkheadStrategy.class);
        }

        @Test
        void should_build_AdaptiveNonBlockingStrategy_for_NonBlockingConfig_with_AIMD() {
            LimitAlgorithm aimd = new AimdLimitAlgorithmConfig(
                    20, 5, 100, 0.9,
                    Duration.ofSeconds(1), 0.05, true, 0.5);

            BulkheadStrategy result = BulkheadStrategyFactory.create(
                    snapshotWith(new AdaptiveNonBlockingStrategyConfig(aimd)),
                    defaultGeneral());

            assertThat(result).isInstanceOf(AdaptiveNonBlockingBulkheadStrategy.class);
        }

        @Test
        void should_build_AdaptiveNonBlockingStrategy_for_NonBlockingConfig_with_Vegas() {
            LimitAlgorithm vegas = new VegasLimitAlgorithmConfig(
                    20, 5, 100,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                    0.05, 0.5);

            BulkheadStrategy result = BulkheadStrategyFactory.create(
                    snapshotWith(new AdaptiveNonBlockingStrategyConfig(vegas)),
                    defaultGeneral());

            assertThat(result).isInstanceOf(AdaptiveNonBlockingBulkheadStrategy.class);
        }
    }

    @Nested
    @DisplayName("snapshot-side parameters")
    class SnapshotParameters {

        @Test
        void semaphore_should_pick_up_the_snapshots_maxConcurrentCalls() {
            // What is to be tested: the snapshot's maxConcurrentCalls drives the semaphore's
            // initial limit. Verifies the factory threads the snapshot value into the
            // constructor — a regression that hard-coded a default would silently change
            // production behaviour for every bulkhead.

            BulkheadSnapshot snapshot = new BulkheadSnapshot(
                    "inventory", 42, Duration.ofMillis(100), Set.of(), null,
                    BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());

            BulkheadStrategy result =
                    BulkheadStrategyFactory.create(snapshot, defaultGeneral());

            assertThat(result.maxConcurrentCalls()).isEqualTo(42);
            assertThat(result.availablePermits()).isEqualTo(42);
        }

        @Test
        void codel_should_pick_up_the_snapshots_maxConcurrentCalls() {
            BulkheadSnapshot snapshot = new BulkheadSnapshot(
                    "inventory", 33, Duration.ofMillis(100), Set.of(), null,
                    BulkheadEventConfig.disabled(),
                    new CoDelStrategyConfig(Duration.ofMillis(50), Duration.ofMillis(500)));

            BulkheadStrategy result =
                    BulkheadStrategyFactory.create(snapshot, defaultGeneral());

            assertThat(result.maxConcurrentCalls()).isEqualTo(33);
        }
    }
}
