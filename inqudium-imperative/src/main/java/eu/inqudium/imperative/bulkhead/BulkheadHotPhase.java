package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.lifecycle.PostCommitInitializable;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;
import eu.inqudium.imperative.lifecycle.spi.HotPhaseMarker;
import eu.inqudium.imperative.lifecycle.spi.ImperativePhase;

/**
 * Hot phase of the lifecycle-aware imperative bulkhead.
 *
 * <p>Carries the actual permit-management state — a {@link SemaphoreBulkheadStrategy}
 * constructed from the snapshot at cold-to-hot transition time. The phase's
 * {@link #execute(long, long, Object, InternalExecutor) execute} acquires a permit, runs the
 * downstream chain, and releases the permit in a {@code finally} block. Acquisition rejections
 * surface as {@link InqBulkheadFullException}; thread interruptions during
 * {@code tryAcquire} surface as {@link InqBulkheadInterruptedException}.
 *
 * <h2>Snapshot subscription (Phase&nbsp;1)</h2>
 *
 * <p>Implements {@link PostCommitInitializable} so the lifecycle base class subscribes the hot
 * phase to live snapshot changes only after the cold-to-hot CAS has committed — discarded
 * candidates therefore leave no listeners behind. The subscription handler always re-reads
 * {@link LiveContainer#snapshot()} to converge to the latest state under concurrent updates,
 * then propagates {@code maxConcurrentCalls} to the strategy via
 * {@link SemaphoreBulkheadStrategy#adjustMaxConcurrent}.
 *
 * <p>This is the in-place adjustment Phase&nbsp;1 supports — the strategy instance is the same
 * before and after the update; only its tunable parameters change. Strategy hot-swaps (replacing
 * the strategy class entirely) require coordination with the veto chain and are Phase&nbsp;2 work.
 */
final class BulkheadHotPhase implements ImperativePhase, HotPhaseMarker, PostCommitInitializable {

    private final InqBulkhead component;
    private final SemaphoreBulkheadStrategy strategy;

    BulkheadHotPhase(InqBulkhead component, BulkheadSnapshot snapshot) {
        // Constructor stays side-effect-free per ADR-029 — no publishes, no subscriptions, no
        // resource acquisition that survives garbage collection. Discarded candidates under CAS
        // contention will be GC'd without any cleanup work.
        this.component = component;
        this.strategy = new SemaphoreBulkheadStrategy(snapshot.maxConcurrentCalls());
    }

    @Override
    public void afterCommit(LiveContainer<?> live) {
        // The lifecycle base class invokes this exactly once after the winning CAS, never on a
        // discarded candidate. Subscribe here so the strategy re-tunes when the snapshot
        // changes.
        @SuppressWarnings("unchecked")
        LiveContainer<BulkheadSnapshot> typed = (LiveContainer<BulkheadSnapshot>) live;
        typed.subscribe(this::onSnapshotChange);
    }

    private void onSnapshotChange(BulkheadSnapshot dispatchedSnapshot) {
        // Re-read the live container instead of trusting the dispatched snapshot. Listener
        // notifications can race under concurrent updates: two threads both CAS-commit, both
        // dispatch, but the second listener may run before the first. Reading the live snapshot
        // inside the handler converges to the latest committed value regardless of dispatch
        // order.
        BulkheadSnapshot latest = component.snapshot();
        strategy.adjustMaxConcurrent(latest.maxConcurrentCalls());
    }

    @Override
    public <A, R> R execute(
            long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        BulkheadSnapshot snap = component.snapshot();
        RejectionContext rejection;
        try {
            rejection = strategy.tryAcquire(snap.maxWaitDuration());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InqBulkheadInterruptedException(chainId, callId, component.name(), false);
        }
        if (rejection != null) {
            throw new InqBulkheadFullException(
                    chainId, callId, component.name(), rejection, false);
        }
        try {
            return next.execute(chainId, callId, argument);
        } finally {
            strategy.release();
        }
    }

    int availablePermits() {
        return strategy.availablePermits();
    }

    int concurrentCalls() {
        return strategy.concurrentCalls();
    }
}
