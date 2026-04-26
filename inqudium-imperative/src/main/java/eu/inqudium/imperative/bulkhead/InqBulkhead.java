package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.time.InqClock;
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
public final class InqBulkhead extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot> {

    /**
     * @param live           the live container holding the bulkhead's snapshot. The component's
     *                       name is read from the snapshot.
     * @param eventPublisher the runtime-scoped publisher. In phase&nbsp;1 the test wiring passes
     *                       this in directly; phase&nbsp;1.7 will source it from
     *                       {@code GeneralSnapshot} (see clarification 4 in REFACTORING.md).
     * @param clock          the wall-clock source for event timestamps. Same sourcing note as
     *                       {@code eventPublisher}.
     */
    public InqBulkhead(
            LiveContainer<BulkheadSnapshot> live,
            InqEventPublisher eventPublisher,
            InqClock clock) {
        super(live.snapshot().name(), InqElementType.BULKHEAD, live, eventPublisher, clock);
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
}
