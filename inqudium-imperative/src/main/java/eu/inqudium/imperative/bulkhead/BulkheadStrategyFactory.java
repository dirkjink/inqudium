package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.snapshot.AdaptiveNonBlockingStrategyConfig;
import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.BulkheadStrategyConfig;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.LimitAlgorithm;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import eu.inqudium.core.element.bulkhead.algo.AimdLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.algo.InqLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.algo.VegasLimitAlgorithm;
import eu.inqudium.core.element.bulkhead.strategy.AdaptiveNonBlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.imperative.bulkhead.strategy.AdaptiveBulkheadStrategy;
import eu.inqudium.imperative.bulkhead.strategy.CoDelBulkheadStrategy;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;

import java.util.function.LongSupplier;

/**
 * Paradigm-internal factory that materializes a concrete {@link BulkheadStrategy} from the
 * snapshot's {@link BulkheadStrategyConfig} (ADR-032).
 *
 * <p>The switch is exhaustive over the sealed {@code BulkheadStrategyConfig} hierarchy — adding
 * a new strategy variant in the future is a compile-time error here until the new branch lands.
 * Same exhaustive guarantee for the inner {@link LimitAlgorithm} switch when materializing the
 * adaptive variants.
 *
 * <p>Package-private and final by design: production code reaches it through
 * {@code BulkheadHotPhase} at hot-materialization time; tests in the same package can
 * exercise it directly without the factory leaking onto the public API.
 */
final class BulkheadStrategyFactory {

    private BulkheadStrategyFactory() {
        // utility class
    }

    /**
     * Materialize the strategy a hot-phase needs to serve its bulkhead.
     *
     * @param snapshot the bulkhead's current snapshot; supplies {@code maxConcurrentCalls} as the
     *                 starting limit and the strategy-config discriminator.
     * @param general  the runtime-level snapshot supplying the cross-cutting collaborators a
     *                 strategy may need: {@link GeneralSnapshot#loggerFactory loggerFactory} for
     *                 strategies that log (CoDel) and the
     *                 {@link GeneralSnapshot#nanoTimeSource nanoTimeSource} that drives both the
     *                 CoDel sojourn measurement and the adaptive algorithms' RTT smoothing.
     * @return a fresh strategy instance bound to the snapshot's config and the supplied
     *         collaborators.
     */
    static BulkheadStrategy create(BulkheadSnapshot snapshot, GeneralSnapshot general) {
        LongSupplier nanoTimeSource = general.nanoTimeSource()::now;
        return switch (snapshot.strategy()) {
            case SemaphoreStrategyConfig ignored ->
                    new SemaphoreBulkheadStrategy(snapshot.maxConcurrentCalls());
            case CoDelStrategyConfig codel ->
                    new CoDelBulkheadStrategy(
                            general.loggerFactory(),
                            snapshot.maxConcurrentCalls(),
                            codel.targetDelay(),
                            codel.interval(),
                            nanoTimeSource);
            case AdaptiveStrategyConfig adaptive ->
                    new AdaptiveBulkheadStrategy(
                            buildAlgorithm(adaptive.algorithm(), nanoTimeSource));
            case AdaptiveNonBlockingStrategyConfig nonBlocking ->
                    new AdaptiveNonBlockingBulkheadStrategy(
                            buildAlgorithm(nonBlocking.algorithm(), nanoTimeSource));
        };
    }

    /**
     * Materialize the limit algorithm an adaptive strategy needs from its config.
     *
     * <p>The snapshot's {@code maxConcurrentCalls} is intentionally not threaded into the
     * algorithm: every adaptive algorithm carries its own {@code initialLimit}, {@code minLimit},
     * {@code maxLimit} and runs its limit on top of those bounds. The snapshot's value is the
     * static-strategy hint, not the adaptive starting point.
     *
     * @param config         the algorithm config — sealed, so the switch stays exhaustive.
     * @param nanoTimeSource the algorithm's smoothing-time-constant clock.
     * @return the wired algorithm.
     */
    private static InqLimitAlgorithm buildAlgorithm(
            LimitAlgorithm config, LongSupplier nanoTimeSource) {
        return switch (config) {
            case AimdLimitAlgorithmConfig aimd -> new AimdLimitAlgorithm(
                    aimd.initialLimit(),
                    aimd.minLimit(),
                    aimd.maxLimit(),
                    aimd.backoffRatio(),
                    aimd.smoothingTimeConstant(),
                    aimd.errorRateThreshold(),
                    aimd.windowedIncrease(),
                    aimd.minUtilizationThreshold(),
                    nanoTimeSource);
            case VegasLimitAlgorithmConfig vegas -> new VegasLimitAlgorithm(
                    vegas.initialLimit(),
                    vegas.minLimit(),
                    vegas.maxLimit(),
                    vegas.smoothingTimeConstant(),
                    vegas.baselineDriftTimeConstant(),
                    vegas.errorRateSmoothingTimeConstant(),
                    vegas.errorRateThreshold(),
                    vegas.minUtilizationThreshold(),
                    nanoTimeSource);
        };
    }
}
