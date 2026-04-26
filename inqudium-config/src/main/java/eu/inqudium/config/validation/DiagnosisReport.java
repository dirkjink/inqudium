package eu.inqudium.config.validation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The structured result of a {@code runtime.diagnose()} call.
 *
 * <p>Carries every {@link DiagnosticFinding} produced by registered
 * {@link CrossComponentRule} instances when invoked against the current
 * {@link eu.inqudium.config.runtime.InqConfigView InqConfigView}. Like {@link BuildReport}, the
 * collections are defensively copied to immutable equivalents so a {@code DiagnosisReport}
 * instance is safe to pass across thread or component boundaries.
 *
 * <p>{@code runtime.diagnose()} is a phase-2 entry point — phase 1 returns an empty report
 * because no cross-component rules are registered yet. The record exists in phase 1 so the
 * runtime contract is complete and consumers can write code against it ahead of phase 2.
 *
 * @param timestamp the moment the report was finalized; non-null.
 * @param findings  diagnostic findings; never null.
 */
public record DiagnosisReport(Instant timestamp, List<DiagnosticFinding> findings) {

    public DiagnosisReport {
        Objects.requireNonNull(timestamp, "timestamp");
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * @return {@code true} iff any finding has severity {@link Severity#ERROR}.
     */
    public boolean hasErrors() {
        for (DiagnosticFinding f : findings) {
            if (f.severity() == Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return all error-level findings.
     */
    public Stream<DiagnosticFinding> errors() {
        return findings.stream().filter(f -> f.severity() == Severity.ERROR);
    }
}
