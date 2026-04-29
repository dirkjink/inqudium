package eu.inqudium.config.validation;

import java.util.Objects;

/**
 * A single class-2 or class-3 validation result, recorded in a {@link BuildReport}.
 *
 * <p>A finding identifies the offending rule, its severity, the component it concerns (or
 * {@code null} for global findings), and a human-readable message. Findings are immutable value
 * objects; instances are aggregated into {@code BuildReport.findings()} and inspected by tooling
 * via {@code BuildReport.warnings()} / {@code BuildReport.isSuccess()}.
 *
 * @param ruleId        the rule's stable identifier (e.g. {@code BULKHEAD_PROTECTIVE_WITH_LONG_WAIT}).
 * @param severity      the rule's effective severity for this finding.
 * @param componentName the component the finding concerns; {@code null} for global findings.
 * @param message       the human-readable explanation; non-null.
 */
public record ValidationFinding(
        String ruleId,
        Severity severity,
        String componentName,
        String message) {

    public ValidationFinding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }
}
