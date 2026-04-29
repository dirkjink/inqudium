package eu.inqudium.config.lifecycle;

import eu.inqudium.config.snapshot.ComponentSnapshot;

import java.util.List;

/**
 * Paradigm-agnostic registration surface for {@link ChangeRequestListener}s on a single
 * component.
 *
 * <p>Every per-paradigm lifecycle base class
 * ({@code ImperativeLifecyclePhasedComponent}, {@code ReactiveLifecyclePhasedComponent}, ...)
 * implements this interface so that the phase-2 update dispatcher in {@code inqudium-config} can
 * iterate the registered listeners through a typed, paradigm-independent reference. The dispatcher
 * never imports a paradigm module.
 *
 * <h2>Lifetime</h2>
 *
 * <p>Listeners are scoped to the handle's lifetime. Registration returns an
 * {@link AutoCloseable} that removes the listener; closing it twice is harmless. After component
 * removal the handle becomes inert and any still-registered listeners are silently discarded —
 * the dispatcher never invokes a listener on a removed component.
 *
 * @param <S> the component's snapshot type.
 */
public interface ListenerRegistry<S extends ComponentSnapshot> {

    /**
     * Register a listener that will be consulted before any hot-state update.
     *
     * <p>The dispatcher iterates registered listeners in registration order before applying any
     * hot-state patch. The chain is conjunctive — the first {@code Veto} rejects the entire
     * patch and the remaining listeners are not consulted.
     *
     * @param listener the listener; non-null.
     * @return an {@link AutoCloseable} that unregisters the listener on close.
     */
    AutoCloseable onChangeRequest(ChangeRequestListener<S> listener);

    /**
     * @return an immutable snapshot of the registered listeners, in registration order. The
     *         dispatcher uses this view to drive the veto chain. Implementations must return a
     *         defensive copy so the dispatcher's iteration is unaffected by concurrent
     *         registrations or removals.
     */
    List<ChangeRequestListener<S>> listeners();
}
