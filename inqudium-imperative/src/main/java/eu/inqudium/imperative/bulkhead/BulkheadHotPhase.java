package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.lifecycle.PostCommitInitializable;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadWaitTraceEvent;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;
import eu.inqudium.imperative.lifecycle.spi.HotPhaseMarker;
import eu.inqudium.imperative.lifecycle.spi.ImperativePhase;

import java.time.Instant;

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
 * <h2>Event publishing (ADR-030, step 1.9)</h2>
 *
 * <p>Per-call events flow through the per-component {@link InqEventPublisher} the bulkhead
 * obtained from {@code GeneralSnapshot.componentPublisherFactory} at construction time. Each
 * event is gated by a flag on the snapshot's {@link BulkheadEventConfig}:
 *
 * <ul>
 *   <li>{@code waitTrace} — sample the nano clock around {@code tryAcquire} and publish a
 *       {@code BulkheadWaitTraceEvent} on both branches (acquired or rejected).</li>
 *   <li>{@code onAcquire} — publish {@code BulkheadOnAcquireEvent} after a successful permit
 *       grant.</li>
 *   <li>{@code onReject} — publish {@code BulkheadOnRejectEvent} on the rejection path before
 *       throwing {@code InqBulkheadFullException}.</li>
 *   <li>{@code onRelease} — publish {@code BulkheadOnReleaseEvent} after the permit is
 *       returned in the finally branch.</li>
 * </ul>
 *
 * <p>The {@code rollbackTrace} flag exists in {@link BulkheadEventConfig} for parity with the
 * pre-refactor implementation but is not yet wired up here — the rollback path covers the case
 * where an event publish itself fails after acquire and the permit must be rolled back, which
 * is a corner case worth its own follow-up. When all flags are off (the default), the hot path
 * pays no event-publishing cost beyond a single {@code anyEnabled()} check.
 *
 * <h2>Snapshot subscription (Phase&nbsp;1)</h2>
 *
 * <p>Implements {@link PostCommitInitializable} so the lifecycle base class subscribes the hot
 * phase to live snapshot changes only after the cold-to-hot CAS has committed — discarded
 * candidates therefore leave no listeners behind. The subscription handler always re-reads
 * {@link LiveContainer#snapshot()} to converge to the latest state under concurrent updates,
 * then propagates {@code maxConcurrentCalls} to the strategy via
 * {@link SemaphoreBulkheadStrategy#adjustMaxConcurrent}.
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
        BulkheadEventConfig events = snap.events();
        InqEventPublisher publisher = component.eventPublisher();

        // Sample wait start only when waitTrace is on so the all-off default does not pay the
        // nanoTime call (which is a non-trivial native call under contention).
        long waitStartNanos = events.waitTrace() ? component.nanoTimeSource().now() : 0L;

        RejectionContext rejection;
        try {
            rejection = strategy.tryAcquire(snap.maxWaitDuration());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InqBulkheadInterruptedException(chainId, callId, component.name(), false);
        }

        if (rejection != null) {
            if (events.waitTrace()) {
                long waitNanos = component.nanoTimeSource().now() - waitStartNanos;
                publisher.publish(new BulkheadWaitTraceEvent(
                        chainId, callId, component.name(), waitNanos, false,
                        component.clock().instant()));
            }
            if (events.onReject()) {
                publisher.publish(new BulkheadOnRejectEvent(
                        chainId, callId, component.name(), rejection,
                        component.clock().instant()));
            }
            throw new InqBulkheadFullException(
                    chainId, callId, component.name(), rejection, false);
        }

        if (events.waitTrace()) {
            long waitNanos = component.nanoTimeSource().now() - waitStartNanos;
            publisher.publish(new BulkheadWaitTraceEvent(
                    chainId, callId, component.name(), waitNanos, true,
                    component.clock().instant()));
        }
        if (events.onAcquire()) {
            publisher.publish(new BulkheadOnAcquireEvent(
                    chainId, callId, component.name(), strategy.concurrentCalls(),
                    component.clock().instant()));
        }

        try {
            return next.execute(chainId, callId, argument);
        } finally {
            strategy.release();
            if (events.onRelease()) {
                Instant timestamp = component.clock().instant();
                publisher.publish(new BulkheadOnReleaseEvent(
                        chainId, callId, component.name(), strategy.concurrentCalls(),
                        timestamp));
            }
        }
    }

    int availablePermits() {
        return strategy.availablePermits();
    }

    int concurrentCalls() {
        return strategy.concurrentCalls();
    }
}
