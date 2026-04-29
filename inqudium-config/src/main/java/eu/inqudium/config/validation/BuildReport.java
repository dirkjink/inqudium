package eu.inqudium.config.validation;

import eu.inqudium.config.runtime.ComponentKey;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The structured result of an initialization or update operation.
 *
 * <p>Returned by {@code Inqudium.configure()...build()}, by {@code runtime.update(...)} and by
 * {@code runtime.dryRun(...)}. Carries every class-2 and class-3 validation finding, every
 * phase-2 veto finding, and the per-component {@link ApplyOutcome}. The default
 * {@link #isSuccess()} predicate considers only error-level findings; warnings do not block
 * success unless {@code strict} mode elevated them.
 *
 * <p>All collections are defensively copied to immutable equivalents in the compact constructor,
 * so a {@code BuildReport} instance is safe to pass across thread or component boundaries.
 *
 * @param timestamp         the moment the report was finalized.
 * @param findings          class-2 and class-3 validation findings; never null.
 * @param vetoFindings      hot-state veto findings produced by the update dispatcher's veto
 *                          chain (ADR-028); never null.
 * @param componentOutcomes per-component apply outcome keyed by {@link ComponentKey} — the
 *                          {@code (name, paradigm)} tuple — so same-name components in different
 *                          paradigms do not collide silently. Never null.
 */
public record BuildReport(
        Instant timestamp,
        List<ValidationFinding> findings,
        List<VetoFinding> vetoFindings,
        Map<ComponentKey, ApplyOutcome> componentOutcomes) {

    public BuildReport {
        Objects.requireNonNull(timestamp, "timestamp");
        findings = findings == null ? List.of() : List.copyOf(findings);
        vetoFindings = vetoFindings == null ? List.of() : List.copyOf(vetoFindings);
        componentOutcomes = componentOutcomes == null ? Map.of() : Map.copyOf(componentOutcomes);
    }

    /**
     * @return {@code true} iff no finding in {@link #findings()} has severity
     *         {@link Severity#ERROR}. Veto findings do not affect success — a vetoed patch is a
     *         policy outcome, not a validation failure, and the runtime continues.
     */
    public boolean isSuccess() {
        for (ValidationFinding f : findings) {
            if (f.severity() == Severity.ERROR) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return all warning-level findings.
     */
    public Stream<ValidationFinding> warnings() {
        return findings.stream().filter(f -> f.severity() == Severity.WARNING);
    }

    /**
     * @return all error-level findings.
     */
    public Stream<ValidationFinding> errors() {
        return findings.stream().filter(f -> f.severity() == Severity.ERROR);
    }
}
