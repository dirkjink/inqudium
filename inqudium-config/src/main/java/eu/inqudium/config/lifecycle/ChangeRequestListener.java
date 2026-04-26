package eu.inqudium.config.lifecycle;

import eu.inqudium.config.snapshot.ComponentSnapshot;

/**
 * Listener consulted before any hot-state update is applied.
 *
 * <p>Listeners are registered per component handle and are scoped to that handle's lifetime. They
 * are invoked in registration order; the chain is conjunctive (a single veto rejects the whole
 * component patch). After all listeners accept, the component-internal mutability check runs as
 * the last gate before {@code LiveContainer.apply(patch)}.
 *
 * <p>Listeners must be cheap. Expensive checks should be precomputed; the framework imposes no
 * timeout on listener execution but every listener adds latency to hot updates.
 *
 * @param <S> the component's snapshot type.
 */
@FunctionalInterface
public interface ChangeRequestListener<S extends ComponentSnapshot> {

    /**
     * @param request describes the proposed patch and the component's current snapshot.
     * @return {@link ChangeDecision#accept()} or
     *         {@link ChangeDecision#veto(String)} with a non-blank reason.
     */
    ChangeDecision decide(ChangeRequest<S> request);
}
