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

    /**
     * Decision for a structural-removal request (ADR-026). The dispatcher iterates listeners
     * with this method when the user calls
     * {@code runtime.update(u -> u.imperative(im -> im.removeBulkhead(...)))} or its analogue.
     *
     * <p>The default implementation returns {@link ChangeDecision#accept()}, so listeners
     * written as lambdas to police snapshot patches do not accidentally veto removals — that
     * concern is opt-in. Listeners that want to veto removals (for example a "do not remove
     * during peak hours" policy) implement this method explicitly through a class-form
     * listener.
     *
     * @param currentSnapshot the component's snapshot at the moment of dispatch.
     * @return {@link ChangeDecision#accept()} or {@link ChangeDecision#veto(String)}.
     */
    default ChangeDecision decideRemoval(S currentSnapshot) {
        return ChangeDecision.accept();
    }
}
