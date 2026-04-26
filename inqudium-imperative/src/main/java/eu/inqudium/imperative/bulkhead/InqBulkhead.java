package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.imperative.lifecycle.ImperativeLifecyclePhasedComponent;
import eu.inqudium.imperative.lifecycle.spi.ImperativePhase;

/**
 * Lifecycle-aware imperative bulkhead built on top of the new configuration architecture
 * (ADR-025, ADR-026, ADR-028, ADR-029).
 *
 * <p>The component holds no per-call state of its own — the cold/hot lifecycle scaffolding lives
 * in the inherited {@link ImperativeLifecyclePhasedComponent} base class, and the actual permit
 * acquisition logic lives in {@link BulkheadHotPhase}. {@code InqBulkhead} provides three things:
 * the bulkhead-specific element type for events, the abstract {@code createHotPhase()} factory
 * method, and phase-aware accessors that read from either the snapshot (cold) or the strategy
 * (hot) depending on the current lifecycle state.
 *
 * <p>Phase&nbsp;1 supports in-place limit adjustments — when the live snapshot's
 * {@code maxConcurrentCalls} changes, the hot phase re-tunes the underlying semaphore. Strategy
 * hot-swaps (semaphore → CoDel, etc.) require the veto chain and are deferred to phase&nbsp;2.
 */
public final class InqBulkhead
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot>
        implements ImperativeBulkhead {

    /**
     * @param live    the live container holding the bulkhead's snapshot. The component's name is
     *                read from the snapshot.
     * @param general the runtime-level snapshot supplying clock and event publisher. The
     *                separate {@code eventPublisher} and {@code clock} parameters from earlier
     *                phases are gone — the {@link GeneralSnapshot} is the single truth source
     *                per clarification&nbsp;4 in {@code REFACTORING.md}.
     */
    public InqBulkhead(
            LiveContainer<BulkheadSnapshot> live,
            GeneralSnapshot general) {
        super(
                live.snapshot().name(),
                InqElementType.BULKHEAD,
                live,
                general.eventPublisher(),
                general.clock());
    }

    @Override
    protected ImperativePhase createHotPhase() {
        // Read the current snapshot at the moment of transition so the hot phase is built from
        // the freshest configuration. Side-effect-free per ADR-029 — no event publishes, no
        // subscription registrations. Subscriptions happen in BulkheadHotPhase#afterCommit
        // after the CAS commits.
        return new BulkheadHotPhase(this, snapshot());
    }

    /**
     * @return the number of permits currently available. When the bulkhead is hot, the value
     *         comes from the live strategy; when cold, it falls back to the snapshot's
     *         {@code maxConcurrentCalls} (no permits have been issued yet).
     */
    public int availablePermits() {
        ImperativePhase p = currentPhase();
        return p instanceof BulkheadHotPhase hot
                ? hot.availablePermits()
                : snapshot().maxConcurrentCalls();
    }

    /**
     * @return the number of permits currently held by in-flight calls. Zero when cold.
     */
    public int concurrentCalls() {
        ImperativePhase p = currentPhase();
        return p instanceof BulkheadHotPhase hot ? hot.concurrentCalls() : 0;
    }

    // TODO: an asynchronous variant of InqBulkhead — analogous to the old InqAsyncDecorator
    //   contract (CompletionStage-based decorate methods, two-phase around-advice with the
    //   release running on stage completion rather than in a synchronous finally) — has not yet
    //   been designed in the new architecture. The phase for this work is undecided; it likely
    //   warrants its own ADR because the cold/hot transition under deferred subscription is not
    //   the same problem the synchronous form solves (the reactive paradigm's deferred CAS
    //   pattern from ADR-029 is closer in spirit). Flagged here so the question is not lost.
}
