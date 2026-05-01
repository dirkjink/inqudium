package eu.inqudium.imperative.bulkhead;

import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.runtime.BulkheadHandle;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.snapshot.AdaptiveNonBlockingStrategyConfig;
import eu.inqudium.config.snapshot.AdaptiveStrategyConfig;
import eu.inqudium.config.snapshot.AimdLimitAlgorithmConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.LimitAlgorithm;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.snapshot.VegasLimitAlgorithmConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqExecutor;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
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
 * <p>Snapshot updates flow through the live container's CAS subscription: an in-place
 * {@code maxConcurrentCalls} change re-tunes the underlying semaphore on the running strategy,
 * and a strategy-config change triggers an atomic strategy swap on the hot phase (after the
 * mutability check has accepted, per ADR-032).
 *
 * <p>Pipeline contracts (ADR-033). The class implements both the synchronous pipeline
 * contracts ({@link InqExecutor} for one-shot execution and {@link InqDecorator} for deferred
 * wrapper construction) and the asynchronous pipeline contract ({@link InqAsyncDecorator})
 * directly: their default methods all reduce to either
 * {@link LayerAction#execute LayerAction.execute(...)} (sync) or
 * {@link eu.inqudium.imperative.core.pipeline.AsyncLayerAction#executeAsync
 * AsyncLayerAction.executeAsync(...)} (async), which the lifecycle base class fulfils via
 * {@link ImperativeLifecyclePhasedComponent#execute} and
 * {@link ImperativeLifecyclePhasedComponent#executeAsync} respectively. The generic
 * {@code <A, R>} parameters propagate from these layer contracts through the lifecycle base
 * into the phase reference.
 *
 * <p>Both paths share the same hot-phase strategy instance, the same listener registry, and
 * the same lifecycle identity — a bulkhead is one component regardless of which method shape
 * its callers use. The async path acquires the permit synchronously on the calling thread
 * (back-pressure semantics) and releases it asynchronously on stage completion via
 * {@code whenComplete}, per ADR-020 and ADR-023. The cold-to-hot trigger fires on the method
 * call moment for both paths: under {@link java.util.concurrent.CompletionStage} semantics,
 * {@code executeAsync} is an eager operation, so by the time the method returns the hot phase
 * exists and its synchronous start-phase work has already happened.
 *
 * @param <A> the call argument type; reduces to {@code Object} for components served via the
 *            runtime registry.
 * @param <R> the call return type; reduces to {@code Object} for components served via the
 *            runtime registry.
 */
public final class InqBulkhead<A, R>
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot, A, R>
        implements BulkheadHandle<ImperativeTag>,
                   InqExecutor<A, R>,
                   InqDecorator<A, R>,
                   InqAsyncDecorator<A, R> {

    private final InqEventPublisher componentEventPublisher;
    private final InqClock clock;
    private final InqNanoTimeSource nanoTimeSource;
    private final GeneralSnapshot general;

    /**
     * @param live    the live container holding the bulkhead's snapshot. The component's name is
     *                read from the snapshot.
     * @param general the runtime-level snapshot supplying clock, event publisher, logger
     *                factory, and component-publisher factory. The {@link GeneralSnapshot} is
     *                the single truth source for these cross-cutting collaborators; this
     *                constructor takes no separate {@code eventPublisher} or {@code clock}
     *                parameters.
     *
     *                <p>Per ADR-030, the per-call component publisher used to emit
     *                {@code BulkheadOnAcquireEvent} et al. is built here from the snapshot's
     *                {@code componentPublisherFactory}, with this bulkhead's name and
     *                {@link InqElementType#BULKHEAD} as its identity. The lifecycle base class
     *                still receives {@code general.eventPublisher()} (the runtime-scoped
     *                publisher) for {@code ComponentBecameHotEvent} and other topology events
     *                — they are intentionally on a different channel.
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
        this.componentEventPublisher = general.componentPublisherFactory()
                .create(live.snapshot().name(), InqElementType.BULKHEAD);
        this.clock = general.clock();
        this.nanoTimeSource = general.nanoTimeSource();
        this.general = general;
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return componentEventPublisher;
    }

    /**
     * @return the wall-clock source used for event timestamps. Package-friendly so
     *         {@link BulkheadHotPhase} can read it; not part of the public API.
     */
    InqClock clock() {
        return clock;
    }

    /**
     * @return the monotonic time source used for wait-duration measurements. Package-friendly
     *         so {@link BulkheadHotPhase} can read it; not part of the public API.
     */
    InqNanoTimeSource nanoTimeSource() {
        return nanoTimeSource;
    }

    /**
     * @return the runtime-level snapshot this bulkhead was constructed from. Package-friendly
     *         so {@link BulkheadHotPhase} can hand it to the strategy factory; not part of the
     *         public API.
     */
    GeneralSnapshot general() {
        return general;
    }

    @Override
    protected ImperativePhase<A, R> createHotPhase() {
        // Read the current snapshot at the moment of transition so the hot phase is built from
        // the freshest configuration. Side-effect-free per ADR-029 — no event publishes, no
        // subscription registrations. Subscriptions happen in BulkheadHotPhase#afterCommit
        // after the CAS commits.
        return new BulkheadHotPhase<>(this, snapshot());
    }

    /**
     * @return the number of permits currently available. When the bulkhead is hot, the value
     *         comes from the live strategy. When cold, the answer depends on the snapshot's
     *         strategy config: semaphore and CoDel both honour {@code maxConcurrentCalls}, so
     *         that snapshot field is the cold-state limit; the adaptive variants run their
     *         own algorithm-driven limit and ignore {@code maxConcurrentCalls} entirely, so
     *         the cold-state accessor reads the algorithm's {@code initialLimit} — the value
     *         the strategy will start with at the cold-to-hot transition. This keeps the
     *         number an observer reads continuous across the transition.
     * @throws eu.inqudium.config.runtime.ComponentRemovedException if this bulkhead has been
     *         structurally removed from the runtime.
     */
    public int availablePermits() {
        ensureNotRemoved();
        ImperativePhase<A, R> p = currentPhase();
        if (p instanceof BulkheadHotPhase<?, ?> hot) {
            return hot.availablePermits();
        }
        return coldPhaseLimit(snapshot());
    }

    /**
     * @return the number of permits currently held by in-flight calls. Zero when cold —
     *         the strategy does not yet exist, so no permit can be in flight.
     * @throws eu.inqudium.config.runtime.ComponentRemovedException if this bulkhead has been
     *         structurally removed from the runtime.
     */
    public int concurrentCalls() {
        ensureNotRemoved();
        ImperativePhase<A, R> p = currentPhase();
        return p instanceof BulkheadHotPhase<?, ?> hot ? hot.concurrentCalls() : 0;
    }

    /**
     * Resolve the cold-state permit limit from a snapshot. Switches exhaustively over the
     * sealed {@link eu.inqudium.config.snapshot.BulkheadStrategyConfig} hierarchy, so adding a
     * new strategy variant produces a compile-time error here until the new branch lands.
     */
    private static int coldPhaseLimit(BulkheadSnapshot snapshot) {
        return switch (snapshot.strategy()) {
            case SemaphoreStrategyConfig ignored -> snapshot.maxConcurrentCalls();
            case CoDelStrategyConfig ignored -> snapshot.maxConcurrentCalls();
            case AdaptiveStrategyConfig adaptive ->
                    algorithmInitialLimit(adaptive.algorithm());
            case AdaptiveNonBlockingStrategyConfig nonBlocking ->
                    algorithmInitialLimit(nonBlocking.algorithm());
        };
    }

    /**
     * Resolve the {@code initialLimit} of an adaptive {@link LimitAlgorithm} config. Switches
     * exhaustively over the sealed hierarchy.
     */
    private static int algorithmInitialLimit(LimitAlgorithm algorithm) {
        return switch (algorithm) {
            case AimdLimitAlgorithmConfig aimd -> aimd.initialLimit();
            case VegasLimitAlgorithmConfig vegas -> vegas.initialLimit();
        };
    }

}
