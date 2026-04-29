package eu.inqudium.config.lifecycle;

import eu.inqudium.config.snapshot.ComponentSnapshot;

/**
 * Component-internal gate that decides whether a proposed patch can be applied to a hot component
 * given its current operational state.
 *
 * <p>The interface is paradigm-agnostic and lives in {@code inqudium-config}; concrete
 * implementations live in the paradigm modules, one per component (for instance, the imperative
 * bulkhead's hot phase implements it to reject strategy-type changes while calls are in flight).
 * The dispatcher invokes the check after the listener chain has accepted, as the last gate before
 * the patch is committed to the live container.
 *
 * <p>This is the component's own line of defence: a listener chain that erroneously accepts a
 * patch which would corrupt the component's internal state cannot bypass the check, because the
 * check is owned by the component, not by the listener registry. The chain remains conjunctive —
 * a single {@link ChangeDecision.Veto Veto} from the check rejects the whole component patch and
 * surfaces in the {@code BuildReport} with {@code Source.COMPONENT_INTERNAL}.
 *
 * <p>Implementations must be cheap and side-effect-free. They are called on the dispatcher's
 * thread, while the live container is being updated.
 *
 * @param <S> the component's snapshot type.
 */
public interface InternalMutabilityCheck<S extends ComponentSnapshot> {

    /**
     * @param request describes the proposed patch and the component's current snapshot.
     * @return {@link ChangeDecision#accept()} if the component can apply the patch in its current
     *         state, or {@link ChangeDecision#veto(String)} with a non-blank reason otherwise.
     */
    ChangeDecision evaluate(ChangeRequest<S> request);

    /**
     * Component-internal removal gate. Consulted by the dispatcher after every listener has
     * accepted a removal (ADR-026), as the last barrier before the container shuts down the
     * component and pulls it from its paradigm map.
     *
     * <p>The default implementation returns {@link ChangeDecision#accept()} — components without
     * structural objections do not need to override it. A bulkhead with in-flight calls that
     * cannot survive removal would return {@link ChangeDecision#veto(String)} with a reason like
     * {@code "permits in flight"}, but that drainage logic is itself a future work item; the
     * 2.3 baseline is unconditional accept.
     *
     * @param currentSnapshot the component's snapshot at the moment of dispatch.
     * @return {@link ChangeDecision#accept()} or {@link ChangeDecision#veto(String)}.
     */
    default ChangeDecision evaluateRemoval(S currentSnapshot) {
        return ChangeDecision.accept();
    }
}
