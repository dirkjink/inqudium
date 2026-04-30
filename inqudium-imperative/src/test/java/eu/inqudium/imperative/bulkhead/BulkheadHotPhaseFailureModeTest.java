package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.dsl.GeneralSnapshotBuilder;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.log.LogAction;
import eu.inqudium.core.log.Logger;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure-mode pinning tests for {@link BulkheadHotPhase} (audit finding 2.12.4 routed into
 * REFACTORING.md sub-step 2.20 follow-up).
 *
 * <p>The test sits inside the bulkhead's own production package so the package-private
 * {@link BulkheadHotPhase#BulkheadHotPhase(InqBulkhead, eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy)
 * BulkheadHotPhase(component, strategy)} seam is reachable without weakening visibility on
 * any production class.
 *
 * <h3>Scope</h3>
 *
 * <p>This file pins the {@code closeStrategy(...)}-throw path of audit finding 2.12.4-B: when
 * the running strategy implements {@link AutoCloseable} and its {@code close()} throws during
 * a strategy hot-swap, the swap must complete (the new strategy takes over, the old one's
 * exception is logged and isolated, no execute call observes the failure).
 *
 * <p>The companion finding 2.12.4-A — strategy construction failure during the cold-to-hot
 * transition — is not pinnable through any seam in the current production code. The
 * {@code BulkheadStrategyConfig} hierarchy is sealed (so a synthetic throwing variant cannot
 * be added), every concrete production strategy's constructor only throws on values that the
 * snapshot's compact constructor already rejects, and {@code BulkheadStrategyFactory} is final
 * with a private constructor (so the factory cannot be substituted). Reaching that finding
 * would require introducing a test-only seam in production code, which is explicitly out of
 * scope for this work item; the gap is reported separately.
 */
@DisplayName("BulkheadHotPhase failure modes (2.12.4)")
class BulkheadHotPhaseFailureModeTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    private static BulkheadSnapshot snapshotOf(
            eu.inqudium.config.snapshot.BulkheadStrategyConfig strategy, int max) {
        return new BulkheadSnapshot(
                "failureMode", max, Duration.ofMillis(100), Set.of(), null,
                BulkheadEventConfig.disabled(), strategy);
    }

    private static GeneralSnapshot generalWithLoggerFactory(LoggerFactory factory) {
        return new GeneralSnapshotBuilder().loggerFactory(factory).build();
    }

    @Nested
    @DisplayName("closeStrategy on hot-swap")
    class CloseStrategyOnHotSwap {

        @Test
        void should_complete_the_swap_when_old_strategys_close_throws() throws Exception {
            // What is to be tested: a hot-phase running a strategy that implements
            // AutoCloseable with a throwing close() method must, on a strategy hot-swap,
            // (1) install the new strategy, (2) catch and isolate the close() failure, and
            // (3) leave subsequent execute calls free of any failure observation. The
            // snapshot-listener handler in BulkheadHotPhase.onSnapshotChange goes through
            // closeStrategy(old) which is the path under test.
            // Why successful: a recording logger captures exactly one warning carrying the
            // close() failure's message, the new strategy is reachable through the next
            // execute, and that execute completes normally.
            // Why important: closeStrategy is a forward-looking code path — none of today's
            // production strategies implement AutoCloseable, so the code is not exercised by
            // any other test. A regression that let close() exceptions propagate out of the
            // listener thread would corrupt the snapshot dispatch chain on the very first
            // strategy that ever needs to clean up resources on swap.

            // Given — start the bulkhead on a CoDel snapshot, then attach a hot phase that
            // carries the throwing-closeable stub. The snapshot-listener will detect the
            // type difference between current strategy (ThrowingCloseableStrategy) and the
            // patched snapshot's strategy config (Semaphore) and trigger a real swap.
            CoDelStrategyConfig initialStrategy = new CoDelStrategyConfig(
                    Duration.ofMillis(10), Duration.ofMillis(100));
            BulkheadSnapshot initial = snapshotOf(initialStrategy, 5);
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(initial);

            CapturingLoggerFactory captured = new CapturingLoggerFactory();
            InqBulkhead<String, String> bh =
                    new InqBulkhead<>(live, generalWithLoggerFactory(captured));

            ThrowingCloseableStrategy throwing = new ThrowingCloseableStrategy();
            BulkheadHotPhase<String, String> phase = new BulkheadHotPhase<>(bh, throwing);

            // Wire the phase into the live container so the snapshot-listener path runs.
            phase.afterCommit(live);

            // When — apply a strategy patch from CoDel to Semaphore. The listener notes the
            // type change, materializes a fresh SemaphoreBulkheadStrategy, and calls
            // closeStrategy(throwing) — which we expect to throw and be swallowed.
            BulkheadPatch swapToSemaphore = new BulkheadPatch();
            swapToSemaphore.touchStrategy(new SemaphoreStrategyConfig());
            live.apply(swapToSemaphore);

            // Then — exactly one warn-level log message carrying the close failure cause.
            assertThat(captured.warnMessages)
                    .as("close() failure must be logged at WARN, not propagated")
                    .hasSize(1);
            assertThat(captured.warnMessages.get(0))
                    .contains("simulated close failure")
                    .contains("failureMode");

            // The throwing strategy was the one whose close() was invoked.
            assertThat(throwing.closeInvocations)
                    .as("closeStrategy must call close() on the swapped-out strategy exactly once")
                    .isEqualTo(1);

            // The next execute runs on the new strategy and observes no fallout from the
            // closed one — chain returns normally.
            String result = phase.execute(1L, 1L, "post-swap", IDENTITY);
            assertThat(result).isEqualTo("post-swap");

            // Defence-in-depth: shutdown the phase to detach the listener so this test does
            // not leak subscriptions across the test runtime.
            phase.shutdown();
        }

        @Test
        void should_not_log_anything_when_closing_a_non_AutoCloseable_strategy() {
            // Companion sanity check: the closeStrategy path is a no-op for production
            // strategies that do not implement AutoCloseable. A regression that always logged
            // would create noise on every legitimate hot-swap. The recording logger receives
            // zero warn messages.

            // Given — start the bulkhead on a CoDel snapshot. The phase initially carries a
            // plain SemaphoreBulkheadStrategy (which does NOT implement AutoCloseable) so a
            // swap will exercise closeStrategy on a non-closeable.
            CoDelStrategyConfig initialStrategy = new CoDelStrategyConfig(
                    Duration.ofMillis(10), Duration.ofMillis(100));
            BulkheadSnapshot initial = snapshotOf(initialStrategy, 5);
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(initial);

            CapturingLoggerFactory captured = new CapturingLoggerFactory();
            InqBulkhead<String, String> bh =
                    new InqBulkhead<>(live, generalWithLoggerFactory(captured));

            BulkheadHotPhase<String, String> phase =
                    new BulkheadHotPhase<>(bh, new SemaphoreBulkheadStrategy(5));
            phase.afterCommit(live);

            // When — patch the snapshot to a different strategy type so the swap branch runs.
            BulkheadPatch swapToCoDel = new BulkheadPatch();
            swapToCoDel.touchStrategy(new CoDelStrategyConfig(
                    Duration.ofMillis(20), Duration.ofMillis(200)));
            live.apply(swapToCoDel);

            // Then — no warn log, swap completed silently.
            assertThat(captured.warnMessages)
                    .as("non-AutoCloseable strategies must not produce a close-failure warning")
                    .isEmpty();

            phase.shutdown();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Test stubs
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Synthetic strategy that implements {@link AutoCloseable} with a throwing
     * {@link AutoCloseable#close() close()}. The bulkhead's
     * {@link BulkheadHotPhase#closeStrategy(eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy)
     * closeStrategy(...)} method does an {@code instanceof AutoCloseable} check, so this stub
     * exercises the otherwise-dormant code path.
     *
     * <p>The strategy itself is permissive: every {@code tryAcquire} succeeds, every
     * {@code release} is a no-op, every accessor returns a sane positive number. The only
     * interesting behaviour is the close throw and the call counter.
     */
    private static final class ThrowingCloseableStrategy
            implements BlockingBulkheadStrategy, AutoCloseable {

        int closeInvocations;

        @Override
        public RejectionContext tryAcquire(Duration timeout) {
            return null;
        }

        @Override
        public void release() {
            // no-op
        }

        @Override
        public void rollback() {
            // no-op
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

        @Override
        public void close() {
            closeInvocations++;
            throw new RuntimeException("simulated close failure");
        }
    }

    /**
     * Logger factory that captures every warn-level message into a list. The capture is
     * scoped to the warn channel because that is where {@code closeStrategy} routes its
     * isolation log; debug/info/error are wired to no-op so unrelated logs do not pollute
     * the assertion.
     */
    private static final class CapturingLoggerFactory implements LoggerFactory {

        final List<String> warnMessages = new ArrayList<>();

        @Override
        public Logger getLogger(Class<?> clazz) {
            LogAction warn = new CapturingLogAction(warnMessages);
            return new Logger(
                    Logger.NO_OP_ACTION,
                    Logger.NO_OP_ACTION,
                    warn,
                    Logger.NO_OP_ACTION);
        }
    }

    private static final class CapturingLogAction implements LogAction {

        private final List<String> sink;

        CapturingLogAction(List<String> sink) {
            this.sink = sink;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void log(String message) {
            sink.add(message);
        }

        @Override
        public void log(String message, Object arg) {
            sink.add(message + ":" + arg);
        }

        @Override
        public void log(String message, Object arg1, Object arg2) {
            sink.add(message + ":" + arg1 + ":" + arg2);
        }

        @Override
        public void log(String message, Object arg1, Object arg2, Object arg3) {
            sink.add(message + ":" + arg1 + ":" + arg2 + ":" + arg3);
        }

        @Override
        public void log(String message, Supplier<?> argSupplier) {
            sink.add(message + ":" + argSupplier.get());
        }

        @Override
        public void log(String message, Object... args) {
            StringBuilder sb = new StringBuilder(message);
            for (Object arg : args) {
                sb.append(":").append(arg);
            }
            sink.add(sb.toString());
        }
    }
}
