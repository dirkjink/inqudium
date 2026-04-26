package eu.inqudium.config.lifecycle;

import java.util.Objects;

/**
 * The decision returned by a {@link ChangeRequestListener} or by the component-internal
 * mutability check.
 *
 * <p>Sealed: only {@link Accept} and {@link Veto} are valid outcomes. A {@code Veto} carries a
 * non-blank reason that is propagated into the {@code BuildReport} and into the
 * {@code RuntimeComponentVetoedEvent}, so an operator investigating "why did my update not
 * apply?" can find the answer immediately.
 *
 * <p>The veto chain is conjunctive (any single veto rejects the entire component patch) and is
 * activated only for hot components. Cold components apply patches without consulting decisions.
 */
public sealed interface ChangeDecision permits ChangeDecision.Accept, ChangeDecision.Veto {

    /**
     * @return the singleton accept decision.
     */
    static ChangeDecision accept() {
        return Accept.INSTANCE;
    }

    /**
     * @param reason the human-readable explanation; must be non-null and non-blank.
     * @return a veto decision carrying the given reason.
     * @throws NullPointerException     if {@code reason} is null.
     * @throws IllegalArgumentException if {@code reason} is blank.
     */
    static ChangeDecision veto(String reason) {
        return new Veto(reason);
    }

    /**
     * The patch is accepted by this listener. Singleton via {@link #INSTANCE}.
     */
    record Accept() implements ChangeDecision {
        static final Accept INSTANCE = new Accept();
    }

    /**
     * The patch is rejected by this listener. The reason must be non-null and non-blank — silent
     * vetoes are debugging nightmares and the framework refuses to allow them.
     */
    record Veto(String reason) implements ChangeDecision {

        public Veto {
            Objects.requireNonNull(reason, "veto reason must not be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("veto reason must not be blank");
            }
        }
    }
}
