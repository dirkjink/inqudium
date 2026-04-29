package eu.inqudium.imperative.lifecycle;

import eu.inqudium.config.event.ComponentBecameHotEvent;
import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.InternalMutabilityCheck;
import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.lifecycle.ListenerRegistry;
import eu.inqudium.config.lifecycle.PostCommitInitializable;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.runtime.ComponentRemovedException;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.imperative.lifecycle.spi.HotPhaseMarker;
import eu.inqudium.imperative.lifecycle.spi.ImperativePhase;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-paradigm lifecycle base class for imperative components (ADR-029).
 *
 * <p>The class holds the lifecycle-stable resources of a component — name, element type, live
 * snapshot container, event publisher, clock, and listener list — and orchestrates the
 * one-directional cold-to-hot transition through an {@link AtomicReference} of the current
 * phase. The phase reference starts pointing at the inner {@code ColdPhase} and is replaced via
 * a single successful {@link AtomicReference#compareAndSet compareAndSet} on the first call to
 * {@link #execute(long, long, Object, InternalExecutor) execute}. After the transition, every
 * subsequent execute call goes directly to the hot phase without re-checking lifecycle state.
 *
 * <h2>The cold-to-hot CAS</h2>
 *
 * <p>Under contention, multiple threads may construct hot-phase candidates concurrently. Only one
 * {@code compareAndSet} succeeds; the discarded candidates are garbage-collected. Hot-phase
 * constructors must therefore be side-effect-free — no event publishes, no subscription
 * registrations, no resource acquisition that survives garbage collection. Side effects that need
 * to run exactly once (subscriptions, scheduled tasks) are deferred to
 * {@link PostCommitInitializable#afterCommit(LiveContainer)}, which the base class invokes on
 * the winning candidate after the CAS commits.
 *
 * <h2>Subclassing contract</h2>
 *
 * <p>Subclasses implement {@link #createHotPhase()} to return their hot-phase instance. The hot
 * phase must implement {@link ImperativePhase} (the execute contract from the lifecycle SPI) and
 * {@link HotPhaseMarker} (so {@link #lifecycleState()} can detect it). It may optionally
 * implement {@link PostCommitInitializable} to receive a one-shot post-commit callback with the
 * component's {@link LiveContainer}.
 *
 * <p>The {@link ListenerRegistry} interface is implemented so the phase-2 update dispatcher in
 * {@code inqudium-config} can iterate listeners through a paradigm-agnostic reference.
 *
 * <p>The {@code A}/{@code R} type parameters propagate the call's argument and return shape into
 * the phase reference and into the {@link #execute(long, long, Object, InternalExecutor) execute}
 * signature (ADR-033). Components that dispatch calls of arbitrary shape — such as
 * {@code InqBulkhead} accessed via the runtime registry — instantiate at
 * {@code <S, Object, Object>} so the inherited execute reduces to the type-erased form callers
 * already use.
 *
 * @param <S> the component's snapshot type.
 * @param <A> the call argument type flowing through the chain.
 * @param <R> the call return type flowing back through the chain.
 */
public abstract class ImperativeLifecyclePhasedComponent<S extends ComponentSnapshot, A, R>
        implements LifecycleAware, ListenerRegistry<S>, InternalMutabilityCheck<S> {

    private final String name;
    private final InqElementType elementType;
    private final LiveContainer<S> live;
    private final InqEventPublisher eventPublisher;
    private final InqClock clock;
    private final CopyOnWriteArrayList<ChangeRequestListener<S>> listeners;
    private final AtomicReference<ImperativePhase<A, R>> phase;

    /**
     * @param name           the component's stable name; non-null.
     * @param elementType    the component's element type; non-null. Used as the {@code elementType}
     *                       of every event published from this component, including
     *                       {@link ComponentBecameHotEvent}.
     * @param live           the live container holding the component's current snapshot; non-null.
     * @param eventPublisher the runtime-scoped publisher used to emit lifecycle events; non-null.
     * @param clock          the wall-clock source for event timestamps; non-null. Inject a
     *                       deterministic clock in tests, never use {@link java.time.Instant#now()}
     *                       directly.
     */
    protected ImperativeLifecyclePhasedComponent(
            String name,
            InqElementType elementType,
            LiveContainer<S> live,
            InqEventPublisher eventPublisher,
            InqClock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.elementType = Objects.requireNonNull(elementType, "elementType");
        this.live = Objects.requireNonNull(live, "live");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listeners = new CopyOnWriteArrayList<>();
        this.phase = new AtomicReference<>(new ColdPhase());
        this.removedPhase = new RemovedPhase<>(this.name, this.elementType);
    }

    /**
     * Construct the hot phase for this component.
     *
     * <p>Called at most a few times per component lifetime — exactly once on the winning CAS, and
     * possibly a handful of additional times on losing CAS candidates that will be discarded.
     * Implementations must be side-effect-free: read the current snapshot via {@link #snapshot()},
     * construct collaborators, return the phase instance. Anything that must happen exactly once
     * belongs in {@link PostCommitInitializable#afterCommit(LiveContainer)}.
     *
     * @return a new hot phase. Must implement {@link ImperativePhase} and {@link HotPhaseMarker}.
     */
    protected abstract ImperativePhase<A, R> createHotPhase();

    @Override
    public final LifecycleState lifecycleState() {
        ImperativePhase<A, R> current = phase.get();
        if (current instanceof RemovedPhase) {
            throw new ComponentRemovedException(name, elementType);
        }
        return current instanceof HotPhaseMarker ? LifecycleState.HOT : LifecycleState.COLD;
    }

    @Override
    public final ChangeDecision evaluate(ChangeRequest<S> request) {
        // Cold phase: the dispatcher does not invoke this on the cold path, but if it ever does
        // (e.g. a future caller bypassing the lifecycle gate), the safe answer is accept —
        // there is nothing the cold component can object to since no in-flight state exists yet.
        //
        // Hot phase: delegate to the phase if it implements the check. Concrete components
        // (BulkheadHotPhase et al.) opt in by implementing InternalMutabilityCheck<S>; phases
        // that do not implement it inherit the conservative accept.
        ImperativePhase<A, R> current = phase.get();
        if (current instanceof InternalMutabilityCheck<?> check) {
            // The phase, when it implements the check, must do so for the same snapshot type S
            // by construction — concrete components extend
            // ImperativeLifecyclePhasedComponent<S> and pair it with an S-typed hot phase.
            @SuppressWarnings("unchecked")
            InternalMutabilityCheck<S> typed = (InternalMutabilityCheck<S>) check;
            return typed.evaluate(request);
        }
        return ChangeDecision.accept();
    }

    /**
     * @return the component's name. Stable across the component's lifetime, including after
     *         {@link #markRemoved()} — the name is what operators reach for in error messages,
     *         so it stays readable on inert handles too.
     */
    public final String name() {
        return name;
    }

    /**
     * @return the component's element type. Stable across the component's lifetime, see
     *         {@link #name()}.
     */
    public final InqElementType elementType() {
        return elementType;
    }

    /**
     * @return the component's current snapshot, read directly from the {@link LiveContainer}.
     * @throws ComponentRemovedException if the component has been
     *         {@linkplain #markRemoved() marked as removed}.
     */
    public final S snapshot() {
        ensureNotRemoved();
        return live.snapshot();
    }

    /**
     * Tear down the component's runtime presence after a successful structural removal
     * (ADR-026). Idempotent — repeated calls install the same removed-phase sentinel and have no
     * additional effect.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>If the current phase implements a {@code shutdown()}-style cleanup (e.g.
     *       {@code BulkheadHotPhase} closes its live-container subscription), invoke it via the
     *       {@link ShutdownAware} marker so live subscriptions stop firing.</li>
     *   <li>Replace the phase reference with a {@link RemovedPhase} sentinel. From that point
     *       on, {@link #execute execute}, {@link #snapshot()}, {@link #lifecycleState()}, and
     *       {@link #evaluate(ChangeRequest)} all raise {@link ComponentRemovedException}, while
     *       {@link #onChangeRequest(ChangeRequestListener) onChangeRequest} silently retains
     *       new registrations on the inert handle until it is garbage-collected (per ADR-028's
     *       "listeners on a removed handle are silently discarded").</li>
     * </ol>
     *
     * <p>Called by {@code DefaultImperative.applyUpdate} after the dispatcher's removal verdict
     * has been accepted; never called from user code.
     */
    public final void markRemoved() {
        ImperativePhase<A, R> current = phase.get();
        if (current instanceof RemovedPhase) {
            return;
        }
        if (current instanceof ShutdownAware shutdown) {
            shutdown.shutdown();
        }
        // CAS rather than set: under contention with a concurrent cold-to-hot CAS the loser of
        // that race could otherwise leak a hot phase past the removal. The retry loop converges:
        // either we win against the cold phase, or we win against the hot phase one transition
        // later, but the RemovedPhase sentinel always wins eventually.
        while (!phase.compareAndSet(current, removedPhase)) {
            current = phase.get();
            if (current instanceof RemovedPhase) {
                return;
            }
            if (current instanceof ShutdownAware shutdown) {
                shutdown.shutdown();
            }
        }
    }

    /**
     * Raise {@link ComponentRemovedException} if this component has been
     * {@linkplain #markRemoved() marked as removed}; otherwise return.
     *
     * <p>Subclasses with phase-aware accessors (the bulkhead's permit counters, future
     * components' equivalents) call this at the start of every external read so a removed
     * handle does not silently report stale or zero values.
     */
    protected final void ensureNotRemoved() {
        if (phase.get() instanceof RemovedPhase) {
            throw new ComponentRemovedException(name, elementType);
        }
    }

    /**
     * Execute the next layer of the chain through this component.
     *
     * <p>Cold path: the {@code ColdPhase} performs the CAS, fires
     * {@link ComponentBecameHotEvent} on success, optionally invokes the post-commit hook on the
     * winning candidate, and then delegates to the (now-installed) hot phase's execute. Hot path:
     * the hot phase's execute runs directly without any lifecycle check.
     *
     * @param chainId  the chain identifier of the call.
     * @param callId   the call identifier.
     * @param argument the argument flowing through the chain.
     * @param next     the next executor in the chain.
     * @return the value produced by the chain after passing through this component.
     */
    public final R execute(
            long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        ImperativePhase<A, R> current = phase.get();
        if (current instanceof RemovedPhase) {
            throw new ComponentRemovedException(name, elementType);
        }
        return current.execute(chainId, callId, argument, next);
    }

    @Override
    public final AutoCloseable onChangeRequest(ChangeRequestListener<S> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public final List<ChangeRequestListener<S>> listeners() {
        return List.copyOf(listeners);
    }

    /**
     * @return the runtime-scoped event publisher passed in at construction time. This is the
     *         publisher carrying lifecycle topology events
     *         ({@link eu.inqudium.config.event.ComponentBecameHotEvent ComponentBecameHotEvent}
     *         and the {@code RuntimeComponent*Event} family) — distinct from the per-component
     *         publisher a concrete subclass exposes via its own
     *         {@code eventPublisher()} method (see ADR-030). The naming
     *         {@code runtimePublisher} avoids the clash with the public per-component accessor
     *         declared on each component's handle interface.
     */
    protected final InqEventPublisher runtimePublisher() {
        return eventPublisher;
    }

    /**
     * @return the live container backing this component.
     */
    protected final LiveContainer<S> live() {
        return live;
    }

    /**
     * @return the current phase. Subclasses use this to expose phase-aware accessors — for
     *         instance, a bulkhead that returns its hot strategy's available permits when hot,
     *         or the snapshot's max permits when cold.
     */
    protected final ImperativePhase<A, R> currentPhase() {
        return phase.get();
    }

    /**
     * The cold phase. Performs the CAS-and-delegate transition exactly once and then becomes
     * unreachable. Non-static so it can read the enclosing component's {@code phase} field
     * directly — this is the whole point of the inner-class form.
     */
    private final class ColdPhase implements ImperativePhase<A, R> {

        @Override
        public R execute(
                long chainId, long callId, A argument, InternalExecutor<A, R> next) {
            ImperativePhase<A, R> hot = createHotPhase();
            if (phase.compareAndSet(this, hot)) {
                eventPublisher.publish(new ComponentBecameHotEvent(
                        chainId, callId, name, elementType, clock.instant()));
                if (hot instanceof PostCommitInitializable post) {
                    post.afterCommit(live);
                }
            }
            return phase.get().execute(chainId, callId, argument, next);
        }
    }

    /**
     * Sentinel installed by {@link #markRemoved()} once a structural removal commits. The
     * lifecycle base's {@link #execute execute} guard catches this phase before delegating, so
     * normal flow never reaches its {@code execute} method. The race between the cold phase's
     * post-CAS re-read and a concurrent {@code markRemoved} can route a call here, however —
     * the explicit {@link ComponentRemovedException} below covers that path with the
     * component's real identity captured at construction time.
     *
     * <p>Static nested form rather than non-static inner because Java's pattern-matching
     * {@code instanceof RemovedPhase} cannot safely refine a generic outer's inner type. The
     * sentinel's type parameters are present only for type-homogeneity of the {@code phase}
     * reference; its {@link #execute execute} body throws unconditionally and never produces an
     * {@code R} value.
     */
    private static final class RemovedPhase<A, R> implements ImperativePhase<A, R> {

        private final String componentName;
        private final InqElementType componentElementType;

        RemovedPhase(String componentName, InqElementType componentElementType) {
            this.componentName = componentName;
            this.componentElementType = componentElementType;
        }

        @Override
        public R execute(
                long chainId, long callId, A argument, InternalExecutor<A, R> next) {
            throw new ComponentRemovedException(componentName, componentElementType);
        }
    }

    private final RemovedPhase<A, R> removedPhase;

    /**
     * Marker interface for hot phases that need to release resources at structural removal —
     * for instance a bulkhead's hot phase closing its live-container subscription. The lifecycle
     * base calls {@link #shutdown()} from {@link #markRemoved()} before installing the
     * {@link RemovedPhase} sentinel.
     */
    public interface ShutdownAware {

        /**
         * Release any resources the phase holds (subscriptions, timers, scheduled tasks).
         * Implementations must be idempotent — the lifecycle base guarantees a single call per
         * removal, but defensive idempotence costs nothing and protects against a future
         * re-entry path.
         */
        void shutdown();
    }
}
