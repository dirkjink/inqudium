package eu.inqudium.config.runtime;

import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.VetoFinding;

import java.util.Objects;
import java.util.Optional;

/**
 * The {@link UpdateDispatcher}'s per-component verdict.
 *
 * <p>Carries the {@link ApplyOutcome} together with an optional {@link VetoFinding}. The two
 * fields are correlated:
 *
 * <ul>
 *   <li>{@link ApplyOutcome#PATCHED PATCHED} or {@link ApplyOutcome#UNCHANGED UNCHANGED} ⇒ no
 *       finding (the patch was accepted; the cold path or the listener chain reached the apply
 *       step).</li>
 *   <li>{@link ApplyOutcome#VETOED VETOED} ⇒ exactly one finding describing why and who vetoed.</li>
 * </ul>
 *
 * <p>The compact constructor enforces this correlation so callers can rely on it without re-
 * checking. Use {@link #applied(ApplyOutcome)} and {@link #vetoed(VetoFinding)} as the canonical
 * factory methods rather than passing literals to the constructor.
 *
 * @param outcome      the per-component outcome.
 * @param vetoFinding  the veto finding when {@code outcome} is {@link ApplyOutcome#VETOED VETOED};
 *                     empty otherwise.
 */
public record DispatchResult(ApplyOutcome outcome, Optional<VetoFinding> vetoFinding) {

    public DispatchResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(vetoFinding, "vetoFinding");
        if (outcome == ApplyOutcome.VETOED && vetoFinding.isEmpty()) {
            throw new IllegalArgumentException(
                    "VETOED outcome must carry a VetoFinding");
        }
        if (outcome != ApplyOutcome.VETOED && vetoFinding.isPresent()) {
            throw new IllegalArgumentException(
                    "non-VETOED outcome must not carry a VetoFinding (was " + outcome + ")");
        }
    }

    /**
     * @param outcome a non-VETOED outcome.
     * @return a result carrying the outcome and an empty {@code vetoFinding}.
     * @throws IllegalArgumentException if {@code outcome} is {@link ApplyOutcome#VETOED VETOED}.
     */
    public static DispatchResult applied(ApplyOutcome outcome) {
        return new DispatchResult(outcome, Optional.empty());
    }

    /**
     * @param finding the veto finding describing the rejection.
     * @return a result tagged {@link ApplyOutcome#VETOED VETOED} carrying {@code finding}.
     */
    public static DispatchResult vetoed(VetoFinding finding) {
        return new DispatchResult(ApplyOutcome.VETOED, Optional.of(finding));
    }
}
