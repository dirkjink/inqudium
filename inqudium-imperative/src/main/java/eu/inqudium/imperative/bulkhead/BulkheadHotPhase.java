package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.lifecycle.InternalMutabilityCheck;
import eu.inqudium.config.lifecycle.PostCommitInitializable;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadField;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.BulkheadStrategyConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.imperative.lifecycle.ImperativeLifecyclePhasedComponent.ShutdownAware;
import eu.inqudium.core.element.bulkhead.BulkheadEventPublishFailureException;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadWaitTraceEvent;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.NonBlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import eu.inqudium.imperative.lifecycle.spi.AsyncImperativePhase;
import eu.inqudium.imperative.lifecycle.spi.HotPhaseMarker;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Hot phase of the lifecycle-aware imperative bulkhead.
 *
 * <p>Carries the actual permit-management state — a {@link BulkheadStrategy} selected by
 * {@link BulkheadStrategyFactory} from the snapshot's
 * {@link eu.inqudium.config.snapshot.BulkheadStrategyConfig} at cold-to-hot transition time.
 * The phase serves both the synchronous and the asynchronous pipeline contracts through a
 * single strategy instance:
 *
 * <ul>
 *   <li>{@link #execute(long, long, Object, InternalExecutor) execute} (sync) acquires a permit,
 *       runs the downstream chain, feeds the strategy's adaptive algorithm with the call's RTT
 *       and success flag, and releases the permit in a {@code finally} block.</li>
 *   <li>{@link #executeAsync(long, long, Object, InternalAsyncExecutor) executeAsync} acquires
 *       the permit synchronously on the calling thread (back-pressure), runs the downstream
 *       async chain, and attaches a release callback to the returned stage so the permit
 *       remains held for the lifetime of the async operation. Per ADR-023 the method returns
 *       the {@code whenComplete} copy. A fast path returns the original stage unchanged when
 *       the downstream stage is already complete on entry.</li>
 * </ul>
 *
 * <p>Acquisition rejections surface as {@link InqBulkheadFullException}; thread interruptions
 * during {@code tryAcquire} surface as {@link InqBulkheadInterruptedException}. Both
 * exceptions are thrown synchronously from both paths — the async path does <em>not</em>
 * convert these to a failed stage, so back-pressure is visible to the caller before any async
 * work begins.
 *
 * <h2>Event publishing (ADR-030)</h2>
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
 * <p>The two publishes that happen while a permit is held — the success-branch
 * {@code BulkheadWaitTraceEvent} and the {@code BulkheadOnAcquireEvent} — go through
 * {@link #publishWhileHoldingPermit publishWhileHoldingPermit}, which guarantees the permit
 * is released even when the publish itself throws. On a publish failure the method also emits
 * a best-effort {@code BulkheadRollbackTraceEvent} when the {@code rollbackTrace} flag is on
 * and rethrows the original failure wrapped in
 * {@link BulkheadEventPublishFailureException} so the caller can distinguish "rejected by
 * capacity" from "accepted but observability stack failed". A second failure on the
 * rollback-trace publish is recorded as a {@code suppressed} exception on the primary so it
 * never masks the original cause.
 *
 * <p>When all flags are off (the default), the hot path pays no event-publishing cost beyond a
 * single {@code anyEnabled()} check.
 *
 * <h2>Snapshot subscription</h2>
 *
 * <p>Implements {@link PostCommitInitializable} so the lifecycle base class subscribes the hot
 * phase to live snapshot changes only after the cold-to-hot CAS has committed — discarded
 * candidates therefore leave no listeners behind. The subscription handler always re-reads
 * {@link LiveContainer#snapshot()} to converge to the latest state under concurrent updates,
 * then takes one of three paths: it materializes a fresh strategy and swaps the reference
 * atomically when the snapshot's strategy config differs from the one the running strategy was
 * last built from (cross-type swap or same-type rebuild with different field values), re-tunes
 * the running strategy in place via {@link SemaphoreBulkheadStrategy#adjustMaxConcurrent} when
 * the active strategy is the semaphore variant, or ignores the change when the active strategy
 * is not in-place tunable (the mutability check vetoes such patches before they reach the live
 * container).
 */
final class BulkheadHotPhase<A, R>
        implements AsyncImperativePhase<A, R>, HotPhaseMarker, PostCommitInitializable,
        InternalMutabilityCheck<BulkheadSnapshot>, ShutdownAware {

    private final InqBulkhead<A, R> component;
    /**
     * The active strategy. Volatile so the snapshot-change handler can swap the reference
     * atomically while in-flight calls retain their already-read strategy: a single volatile
     * write is the atomicity boundary; readers either see the old strategy or the new one,
     * never a torn intermediate. ADR-032's strategy-hot-swap contract is built on this single
     * write.
     */
    private volatile BulkheadStrategy strategy;
    /**
     * The {@link BulkheadStrategyConfig} the {@link #strategy} field was last materialized from.
     * Updated on every swap — both the cross-type swap and the same-type rebuild — so that
     * {@link #strategyChanged(BulkheadSnapshot)} can detect both flavours by structural record
     * equality. Volatile because it is read on the snapshot-listener thread and written on the
     * same thread, but may be observed across listener invocations on different threads under
     * update concurrency. {@code null} only when the test seam constructor was used with a
     * pre-built strategy that was never derived from a config; in that case the first
     * snapshot-change event always treats the change as a swap, which matches the existing
     * test seam expectations.
     */
    private volatile BulkheadStrategyConfig lastMaterializedConfig;
    /**
     * Live-container subscription registered in {@link #afterCommit(LiveContainer)} and released
     * in {@link #shutdown()}. Volatile because the writer (afterCommit, on the cold-to-hot CAS
     * winner) and the reader (shutdown, on the dispatcher thread serving a removal) are
     * different threads with no other happens-before edge between them.
     */
    private volatile AutoCloseable subscription;

    BulkheadHotPhase(InqBulkhead<A, R> component, BulkheadSnapshot snapshot) {
        // Constructor stays side-effect-free per ADR-029 — no publishes, no subscriptions, no
        // resource acquisition that survives garbage collection. Discarded candidates under CAS
        // contention will be GC'd without any cleanup work. Strategy materialization runs through
        // the paradigm-internal factory so the choice between Semaphore / CoDel / Adaptive (and
        // the AIMD vs Vegas algorithm picks) lands in one exhaustive switch — adding a new
        // strategy variant is a compile-time error there until the new branch lands (ADR-032).
        this.component = component;
        this.strategy = BulkheadStrategyFactory.create(snapshot, component.general());
        this.lastMaterializedConfig = snapshot.strategy();
    }

    /**
     * Package-private seam used by tests that need to plug a stub {@link BulkheadStrategy}
     * (e.g. one that observes ordering of {@code onCallComplete} versus {@code release}, or one
     * that simulates an algorithm failure thrown out of {@code onCallComplete}). Production code
     * always goes through the snapshot-based constructor, which materializes a real strategy via
     * {@link BulkheadStrategyFactory}.
     *
     * <p>{@link #lastMaterializedConfig} stays {@code null} on this path: the seam strategy was
     * not materialized from a config, so the first snapshot-change event compared via
     * {@link #strategyChanged(BulkheadSnapshot)} unambiguously sees a difference and routes
     * through the swap branch — which is what the existing seam tests expect.
     */
    BulkheadHotPhase(InqBulkhead<A, R> component, BulkheadStrategy strategy) {
        this.component = component;
        this.strategy = strategy;
    }

    @Override
    public void afterCommit(LiveContainer<?> live) {
        // The lifecycle base class invokes this exactly once after the winning CAS, never on a
        // discarded candidate. Subscribe here so the strategy re-tunes when the snapshot
        // changes; capture the AutoCloseable so {@link #shutdown()} can stop the subscription
        // when the component is structurally removed.
        @SuppressWarnings("unchecked")
        LiveContainer<BulkheadSnapshot> typed = (LiveContainer<BulkheadSnapshot>) live;
        this.subscription = typed.subscribe(this::onSnapshotChange);
    }

    @Override
    public void shutdown() {
        // Idempotent: clearing the field after close means a second shutdown call is a no-op.
        // Concurrent observers reading {@code subscription == null} likewise short-circuit.
        AutoCloseable handle = this.subscription;
        if (handle == null) {
            return;
        }
        this.subscription = null;
        try {
            handle.close();
        } catch (Exception ignored) {
            // Best-effort: subscribe()'s AutoCloseable just removes the listener from a
            // CopyOnWriteArrayList; close() does not throw in practice. Swallowing here keeps
            // the removal path from failing on a hypothetical future implementation that does.
        }
    }

    /**
     * Bridge the {@link BulkheadStrategy} hierarchy back to a single point of view: blocking
     * strategies park up to {@code maxWaitDuration} via {@code tryAcquire(Duration)},
     * non-blocking strategies return immediately via {@code tryAcquire()} and ignore the wait
     * duration entirely. The hot phase calls one site; the dispatch happens here.
     *
     * <p>{@link InterruptedException} can only originate on the blocking branch; the
     * non-blocking branch never throws. The {@code throws} declaration stays on the bridge
     * because the call site needs the same {@code InqBulkheadInterruptedException} translation
     * either way.
     */
    private RejectionContext tryAcquire(Duration maxWaitDuration)
            throws InterruptedException {
        if (strategy instanceof BlockingBulkheadStrategy blocking) {
            return blocking.tryAcquire(maxWaitDuration);
        }
        if (strategy instanceof NonBlockingBulkheadStrategy nonBlocking) {
            return nonBlocking.tryAcquire();
        }
        throw new IllegalStateException(
                "BulkheadStrategy must implement BlockingBulkheadStrategy or "
                        + "NonBlockingBulkheadStrategy, was " + strategy.getClass().getName());
    }

    private void onSnapshotChange(BulkheadSnapshot dispatchedSnapshot) {
        // Re-read the live container instead of trusting the dispatched snapshot. Listener
        // notifications can race under concurrent updates: two threads both CAS-commit, both
        // dispatch, but the second listener may run before the first. Reading the live snapshot
        // inside the handler converges to the latest committed value regardless of dispatch
        // order.
        BulkheadSnapshot latest = component.snapshot();

        // Strategy hot-swap path: the snapshot's strategy config differs from the strategy the
        // hot phase is currently running. The mutability check has already verified zero
        // in-flight calls, so the swap is safe to commit. A single volatile write is the
        // atomicity boundary — readers either see the old strategy or the new one, never a
        // torn intermediate.
        if (strategyChanged(latest)) {
            BulkheadStrategy old = this.strategy;
            this.strategy = BulkheadStrategyFactory.create(latest, component.general());
            this.lastMaterializedConfig = latest.strategy();
            closeStrategy(old);
            return;
        }

        // No strategy swap, just an in-place re-tune. adjustMaxConcurrent is Semaphore-only;
        // for non-semaphore strategies, MAX_CONCURRENT_CALLS patches are vetoed by evaluate()
        // before they reach this branch. The instanceof guard remains as defence-in-depth so
        // an unexpected snapshot still keeps the bulkhead in a coherent state.
        if (strategy instanceof SemaphoreBulkheadStrategy semaphore) {
            semaphore.adjustMaxConcurrent(latest.maxConcurrentCalls());
        }
    }

    /**
     * Detect whether the latest snapshot's strategy config differs from the one the running
     * {@link #strategy} was last materialized from.
     *
     * <p>The comparison runs on the cached {@link #lastMaterializedConfig} record by structural
     * equality. {@link BulkheadStrategyConfig} is sealed and every permitted variant is a
     * record, so {@link Objects#equals} settles all three cases uniformly:
     *
     * <ul>
     *   <li>same type, same fields — equal, returns {@code false}, no swap;</li>
     *   <li>same type, different fields — unequal (e.g. CoDel(50ms) vs CoDel(80ms),
     *       AIMD vs Vegas inside an adaptive variant) — returns {@code true}, swap path
     *       runs and the running strategy is rebuilt from the new config;</li>
     *   <li>different type — unequal, returns {@code true}, swap path runs.</li>
     * </ul>
     *
     * <p>The earlier implementation switched on the latest config and tested
     * {@code instanceof} against {@link #strategy} — this missed the same-type-different-field
     * case (TODO.md "Strategy config tweaks without strategy-type change"), where the snapshot
     * carried the new fields but the running strategy silently kept the old ones.
     */
    private boolean strategyChanged(BulkheadSnapshot latest) {
        return !Objects.equals(this.lastMaterializedConfig, latest.strategy());
    }

    /**
     * Best-effort close of a strategy that has just been swapped out. The base
     * {@link BulkheadStrategy} interface does not extend {@link AutoCloseable}, but a future
     * implementation might via subtyping; the runtime-type check here is forward-looking and
     * harmless when the type does not implement the interface (the call simply skips).
     *
     * <p>A throw on close is logged and swallowed: the swap has already happened, the new
     * strategy is in place, and the only remaining question is whether the old strategy left
     * resources behind. That diagnostic belongs in the log; it must not propagate to the
     * snapshot-listener thread that triggered the swap.
     */
    private void closeStrategy(BulkheadStrategy old) {
        if (!(old instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            component.general().loggerFactory()
                    .getLogger(BulkheadHotPhase.class)
                    .warn().log(
                            "Strategy close on swap failed for bulkhead '"
                                    + component.name() + "': " + ex.getMessage());
        }
    }

    @Override
    public ChangeDecision evaluate(ChangeRequest<BulkheadSnapshot> request) {
        // The mutability check is the last gate before apply. It evaluates against the
        // post-patch snapshot for fields whose live-tunability depends on the post-patch
        // strategy (MAX_CONCURRENT_CALLS), and against runtime state for transition
        // operations whose precondition is "no in-flight work" (STRATEGY).
        Set<? extends ComponentField> touched = request.touchedFields();
        BulkheadSnapshot postPatch = request.postPatchSnapshot();

        // STRATEGY swap is a transition operation. The precondition is the runtime state at
        // evaluation time — the snapshot says what the swap target is, but the swap can only
        // commit when no permits are held. Otherwise the in-flight callers' release path would
        // touch the new strategy with state allocated against the old one.
        if (touched.contains(BulkheadField.STRATEGY)) {
            int inFlight = strategy.concurrentCalls();
            if (inFlight > 0) {
                return ChangeDecision.veto(
                        "strategy swap requires zero in-flight calls; current = " + inFlight);
            }
        }

        // MAX_CONCURRENT_CALLS is live-tunable only when the post-patch strategy is the
        // semaphore. CoDel pins its concurrency at construction; the adaptive variants run
        // their own algorithm-driven limit. Evaluating against post-patch (rather than the
        // current strategy) lets a combined STRATEGY=Semaphore + MAX_CONCURRENT_CALLS=N patch
        // pass — the user is moving onto a strategy where the field is live-tunable.
        if (touched.contains(BulkheadField.MAX_CONCURRENT_CALLS)
                && !(postPatch.strategy() instanceof SemaphoreStrategyConfig)) {
            return ChangeDecision.veto(
                    "maxConcurrentCalls is not live-tunable on "
                            + postPatch.strategy().getClass().getSimpleName()
                            + " — either swap to SemaphoreStrategyConfig in the same patch "
                            + "or recreate the bulkhead");
        }

        // Other fields (NAME, MAX_WAIT_DURATION, TAGS, EVENTS, DERIVED_FROM_PRESET) are read
        // fresh from the snapshot on every execute or hot-publish; no strategy mutation
        // required, conservative-accept stays correct.
        return ChangeDecision.accept();
    }

    @Override
    public ChangeDecision evaluateRemoval(BulkheadSnapshot currentSnapshot) {
        // 2.3 baseline: accept removals unconditionally. The default in the interface already
        // accepts; this explicit override exists so future drainage logic has a clear home.
        // Drainage extension point: when ADR-026 grows in-flight-call drainage, this method
        // grows a veto branch that consults strategy.concurrentCalls() — "permits in flight,
        // try again after drain" — paired with whatever drainage mechanism the runtime chooses
        // (timeout-with-grace, force-after-deadline, or block-until-empty). Until then, removal
        // is best-effort: in-flight calls finish on the strategy reference they hold, the
        // handle goes inert, and new acquires are rejected via ComponentRemovedException.
        return ChangeDecision.accept();
    }

    @Override
    public R execute(
            long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        BulkheadSnapshot snap = component.snapshot();
        BulkheadEventConfig events = snap.events();
        InqEventPublisher publisher = component.eventPublisher();
        boolean optimizeException = component.general().enableExceptionOptimization();

        // Sample wait start only when waitTrace is on so the all-off default does not pay the
        // nanoTime call (which is a non-trivial native call under contention).
        long waitStartNanos = events.waitTrace() ? component.nanoTimeSource().now() : 0L;

        RejectionContext rejection;
        try {
            rejection = tryAcquire(snap.maxWaitDuration());
        } catch (InterruptedException ie) {
            // Sync path leaves wait-trace and reject events unpublished on interrupt — preserved
            // from the pre-refactor behaviour. The async path differs and publishes a
            // wait-trace acquired=false via handleAcquireFailure(rejection=null).
            Thread.currentThread().interrupt();
            throw new InqBulkheadInterruptedException(
                    chainId, callId, component.name(), optimizeException);
        }

        if (rejection != null) {
            handleAcquireFailure(chainId, callId, events, publisher, waitStartNanos, rejection);
            throw new InqBulkheadFullException(
                    chainId, callId, component.name(), rejection, optimizeException);
        }

        handleAcquireSuccess(chainId, callId, events, publisher, waitStartNanos);

        // Sample RTT around the downstream call so the strategy can feed its adaptive algorithm
        // (ADR-020). Adaptive algorithms read in-flight count plus RTT to decide whether to
        // raise or lower the limit; ordering matters — onCallComplete runs before release so
        // the algorithm sees the in-flight count it actually served, not the post-release
        // count one lower than that. A failure inside onCallComplete (an algorithm bug) is
        // logged via the runtime's loggerFactory and swallowed: a permit leak would corrupt
        // the bulkhead far worse than a missed limit-update sample.
        long startNanos = component.nanoTimeSource().now();
        Throwable businessError = null;
        try {
            return next.execute(chainId, callId, argument);
        } catch (Throwable t) {
            businessError = t;
            throw t;
        } finally {
            long rttNanos = component.nanoTimeSource().now() - startNanos;
            releaseAndReport(chainId, callId, rttNanos, businessError);
        }
    }

    /**
     * Async counterpart to {@link #execute}. Acquires the permit synchronously on the calling
     * thread (back-pressure: rejection or interrupt surface as a synchronous throw before any
     * stage exists), runs the downstream {@link InternalAsyncExecutor#executeAsync}, and attaches
     * a release callback to the returned stage so the permit is held for the entire lifetime
     * of the async operation.
     *
     * <p>Per ADR-023, the method returns the decorated copy produced by
     * {@link CompletionStage#whenComplete whenComplete} so an exception thrown inside the
     * release callback surfaces on the caller's stage rather than disappearing on a detached
     * branch. Fast path: if the downstream stage is already complete on entry (sync-wrapped
     * async, caching, validation failure), the release runs inline and the original stage is
     * returned unchanged — no callback attachment, no extra stage allocation.
     *
     * <p>The release callback runs on whatever thread completes the downstream stage. Strategy
     * accounting and event publishing are thread-safe; the strategy's {@code release()} is
     * idempotent across threads, the per-component publisher is thread-safe per ADR-030.
     */
    @Override
    public CompletionStage<R> executeAsync(
            long chainId, long callId, A argument, InternalAsyncExecutor<A, R> next) {
        BulkheadSnapshot snap = component.snapshot();
        BulkheadEventConfig events = snap.events();
        InqEventPublisher publisher = component.eventPublisher();
        boolean optimizeException = component.general().enableExceptionOptimization();

        long waitStartNanos = events.waitTrace() ? component.nanoTimeSource().now() : 0L;

        RejectionContext rejection;
        try {
            rejection = tryAcquire(snap.maxWaitDuration());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            handleAcquireFailure(chainId, callId, events, publisher, waitStartNanos, null);
            throw new InqBulkheadInterruptedException(
                    chainId, callId, component.name(), optimizeException);
        }

        if (rejection != null) {
            handleAcquireFailure(chainId, callId, events, publisher, waitStartNanos, rejection);
            throw new InqBulkheadFullException(
                    chainId, callId, component.name(), rejection, optimizeException);
        }

        handleAcquireSuccess(chainId, callId, events, publisher, waitStartNanos);

        long startNanos = component.nanoTimeSource().now();
        CompletionStage<R> stage;
        try {
            stage = next.executeAsync(chainId, callId, argument);
        } catch (Throwable t) {
            // Synchronous throw during stage construction. Without this catch, the permit
            // would leak — the whenComplete callback never runs because no stage was returned.
            // Throwable is the right catch: any sync failure (including Error) must release;
            // the rethrow leaves Error propagation intact.
            long rttNanos = component.nanoTimeSource().now() - startNanos;
            releaseAndReport(chainId, callId, rttNanos, t);
            throw t;
        }

        // Fast path: an already-completed CompletableFuture (sync-wrapped-as-async, caching,
        // validation failure) gets the release inline and the original stage back. No
        // intermediate stage is allocated, no callback is attached.
        if (stage instanceof CompletableFuture<?> cf && cf.isDone()) {
            long rttNanos = component.nanoTimeSource().now() - startNanos;
            releaseAndReport(chainId, callId, rttNanos, completionError(cf));
            return stage;
        }

        // Slow path: pending stage. The release callback runs on the completion thread. ADR-023:
        // return the whenComplete copy so a throw inside the callback surfaces on the returned
        // stage rather than getting swallowed on a detached branch.
        return stage.whenComplete((result, error) -> {
            long rttNanos = component.nanoTimeSource().now() - startNanos;
            releaseAndReport(chainId, callId, rttNanos, error);
        });
    }

    /**
     * Reads the failure of an already-completed {@link CompletableFuture} without blocking.
     * Returns {@code null} if the future completed successfully. Only called on the fast path,
     * after {@code cf.isDone()} returned {@code true}, so {@code getNow} resolves immediately.
     */
    private static Throwable completionError(CompletableFuture<?> cf) {
        try {
            cf.getNow(null);
            return null;
        } catch (CompletionException ce) {
            return ce.getCause();
        } catch (CancellationException ce) {
            return ce;
        }
    }

    /**
     * Publish wait-trace (acquired=false) and on-reject events for an acquire that did not
     * succeed. Used by both the sync rejection path and the async rejection / interrupt paths.
     *
     * <p>The {@code rejection} parameter is {@code null} on the async interrupt path —
     * the wait-trace publish runs anyway (the wait happened) but the on-reject publish does
     * not (interrupt is not a capacity rejection). Sync's interrupt path bypasses this helper
     * entirely to preserve its pre-refactor "no events on interrupt" behaviour.
     */
    private void handleAcquireFailure(
            long chainId,
            long callId,
            BulkheadEventConfig events,
            InqEventPublisher publisher,
            long waitStartNanos,
            RejectionContext rejection) {
        if (events.waitTrace()) {
            long waitNanos = component.nanoTimeSource().now() - waitStartNanos;
            publisher.publish(new BulkheadWaitTraceEvent(
                    chainId, callId, component.name(), waitNanos, false,
                    component.clock().instant()));
        }
        if (rejection != null && events.onReject()) {
            publisher.publish(new BulkheadOnRejectEvent(
                    chainId, callId, component.name(), rejection,
                    component.clock().instant()));
        }
    }

    /**
     * Publish wait-trace (acquired=true) and on-acquire events for a successful acquire. Both
     * publishes run while the permit is held — a throw on either path must release the permit
     * via {@link #publishWhileHoldingPermit}, which is shared with the sync rollback path.
     */
    private void handleAcquireSuccess(
            long chainId,
            long callId,
            BulkheadEventConfig events,
            InqEventPublisher publisher,
            long waitStartNanos) {
        if (events.waitTrace()) {
            long waitNanos = component.nanoTimeSource().now() - waitStartNanos;
            publishWhileHoldingPermit(
                    publisher, events,
                    new BulkheadWaitTraceEvent(
                            chainId, callId, component.name(), waitNanos, true,
                            component.clock().instant()),
                    chainId, callId);
        }
        if (events.onAcquire()) {
            publishWhileHoldingPermit(
                    publisher, events,
                    new BulkheadOnAcquireEvent(
                            chainId, callId, component.name(), strategy.concurrentCalls(),
                            component.clock().instant()),
                    chainId, callId);
        }
    }

    /**
     * Run the post-call work: feed the strategy's adaptive algorithm with the call's RTT and
     * success flag, release the permit, then publish the on-release event if configured. Used
     * by both the sync finally path and the async whenComplete callback. The on-release publish
     * deliberately re-reads the current snapshot's events: in the async path the snapshot may
     * have been updated between acquire and release, and the user's expectation is that the
     * release event reflects the latest configuration, not a stale capture from the start
     * phase.
     *
     * <p>The method is safe to invoke from any thread — the strategy's release operation is
     * thread-safe and the per-component publisher is thread-safe per ADR-030.
     */
    private void releaseAndReport(
            long chainId, long callId, long rttNanos, Throwable businessError) {
        try {
            strategy.onCallComplete(rttNanos, businessError == null);
        } catch (RuntimeException algorithmFailure) {
            component.general().loggerFactory()
                    .getLogger(BulkheadHotPhase.class)
                    .error().log(
                            "Adaptive algorithm hook failed for bulkhead '"
                                    + component.name() + "', callId=" + callId
                                    + ". Permit will still be released. Cause: "
                                    + algorithmFailure);
        }
        strategy.release();

        BulkheadEventConfig events = component.snapshot().events();
        if (events.onRelease()) {
            component.eventPublisher().publish(new BulkheadOnReleaseEvent(
                    chainId, callId, component.name(), strategy.concurrentCalls(),
                    component.clock().instant()));
        }
    }

    /**
     * Publish an event that runs after a successful permit acquire and before the user's
     * downstream lambda starts. A throw on publish releases the permit, optionally emits a
     * {@link BulkheadRollbackTraceEvent} (gated by {@link BulkheadEventConfig#rollbackTrace}),
     * and rethrows as {@link BulkheadEventPublishFailureException} with the original cause.
     *
     * <p>Rollback-trace publish is best-effort: when the rollback-trace flag is on the method
     * tries the republish on the same publisher. A second failure on the rollback-trace path is
     * recorded as a {@code suppressed} exception on the primary failure rather than thrown — the
     * primary publish failure is what the caller asked for, and a noisy secondary throw would
     * mask it. Fatal errors ({@link VirtualMachineError}, {@link ThreadDeath},
     * {@link LinkageError}) are rethrown unchanged so the JVM can act on them; the permit is
     * released first to keep the bulkhead's accounting consistent even on the way down.
     */
    private void publishWhileHoldingPermit(
            InqEventPublisher publisher,
            BulkheadEventConfig events,
            InqEvent event,
            long chainId,
            long callId) {
        try {
            publisher.publish(event);
        } catch (Throwable primary) {
            // Permit must be released regardless of what comes next, including a fatal error
            // that we are about to rethrow — leaking on the way to OOM still corrupts the
            // bulkhead's permit count if the JVM somehow recovers.
            strategy.release();
            InqException.rethrowIfFatal(primary);

            if (events.rollbackTrace()) {
                try {
                    publisher.publish(new BulkheadRollbackTraceEvent(
                            chainId, callId, component.name(),
                            primary.getClass().getName(),
                            component.clock().instant()));
                } catch (Throwable secondary) {
                    InqException.rethrowIfFatal(secondary);
                    // Best-effort: surface the secondary on the primary as suppressed so an
                    // operator inspecting the cause chain still sees both, but never replace the
                    // primary failure with the secondary.
                    primary.addSuppressed(secondary);
                }
            }

            throw new BulkheadEventPublishFailureException(
                    chainId, callId, component.name(),
                    event.getClass().getSimpleName(), primary);
        }
    }

    int availablePermits() {
        return strategy.availablePermits();
    }

    int concurrentCalls() {
        return strategy.concurrentCalls();
    }
}
