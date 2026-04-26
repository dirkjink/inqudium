package eu.inqudium.imperative.lifecycle;

import eu.inqudium.config.event.ComponentBecameHotEvent;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.lifecycle.PostCommitInitializable;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.time.InqClock;

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
 * phase must implement {@link ImperativePhase} (the execute contract) and
 * {@link HotPhaseMarker} (so {@link #lifecycleState()} can detect it). It may optionally
 * implement {@link PostCommitInitializable} to receive a one-shot post-commit callback with the
 * component's {@link LiveContainer}.
 *
 * @param <S> the component's snapshot type.
 */
public abstract class ImperativeLifecyclePhasedComponent<S extends ComponentSnapshot>
        implements LifecycleAware {

    private final String name;
    private final InqElementType elementType;
    private final LiveContainer<S> live;
    private final InqEventPublisher eventPublisher;
    private final InqClock clock;
    private final CopyOnWriteArrayList<ChangeRequestListener<S>> listeners;
    private final AtomicReference<ImperativePhase> phase;

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
    protected abstract ImperativePhase createHotPhase();

    @Override
    public final LifecycleState lifecycleState() {
        return phase.get() instanceof HotPhaseMarker ? LifecycleState.HOT : LifecycleState.COLD;
    }

    /**
     * @return the component's name.
     */
    public final String name() {
        return name;
    }

    /**
     * @return the component's current snapshot, read directly from the {@link LiveContainer}.
     */
    public final S snapshot() {
        return live.snapshot();
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
     * @param <A>      the argument type.
     * @param <R>      the return type.
     * @return the value produced by the chain after passing through this component.
     */
    public final <A, R> R execute(
            long chainId, long callId, A argument, InternalExecutor<A, R> next) {
        return phase.get().execute(chainId, callId, argument, next);
    }

    /**
     * Register a {@link ChangeRequestListener} that will be consulted before any hot-state update.
     *
     * <p>Listener invocation is wired up in phase&nbsp;2 of the configuration refactor; in
     * phase&nbsp;1 the listener list is stored but never read. Registration order is preserved.
     *
     * @param listener the listener; non-null.
     * @return an {@link AutoCloseable} that unregisters the listener on close.
     */
    public final AutoCloseable onChangeRequest(ChangeRequestListener<S> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * @return an immutable snapshot of the registered listeners, in registration order. Used by
     *         the phase-2 dispatcher to drive the veto chain.
     */
    final List<ChangeRequestListener<S>> listeners() {
        return List.copyOf(listeners);
    }

    /**
     * @return the runtime-scoped event publisher passed in at construction time.
     */
    protected final InqEventPublisher eventPublisher() {
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
    protected final ImperativePhase currentPhase() {
        return phase.get();
    }

    /**
     * The internal phase contract. The cold phase delegates to a freshly constructed hot phase
     * after a successful CAS; the hot phase runs the component-specific execute logic directly.
     */
    public interface ImperativePhase {

        /**
         * @param chainId  the chain identifier of the call.
         * @param callId   the call identifier.
         * @param argument the argument flowing through the chain.
         * @param next     the next executor in the chain.
         * @param <A>      the argument type.
         * @param <R>      the return type.
         * @return the value produced by the chain.
         */
        <A, R> R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next);
    }

    /**
     * Marker implemented by every hot phase so the base class can detect the hot state through
     * {@code instanceof} without knowing concrete subtypes.
     */
    public interface HotPhaseMarker {
    }

    /**
     * The cold phase. Performs the CAS-and-delegate transition exactly once and then becomes
     * unreachable. Non-static so it can read the enclosing component's {@code phase} field
     * directly — this is the whole point of the inner-class form.
     */
    private final class ColdPhase implements ImperativePhase {

        @Override
        public <A, R> R execute(
                long chainId, long callId, A argument, InternalExecutor<A, R> next) {
            ImperativePhase hot = createHotPhase();
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
}
