package eu.inqudium.config.validation;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A single class-4 (cross-component) finding produced by {@link CrossComponentRule#check} and
 * carried in the {@code DiagnosisReport} returned by {@code runtime.diagnose()}.
 *
 * <p>Unlike a {@link ValidationFinding}, a diagnostic finding may reference multiple components —
 * cross-component issues by definition involve more than one. The optional structured
 * {@code context} map carries machine-readable detail for operational dashboards; the
 * {@code message} is for humans.
 *
 * @param ruleId              the rule's stable identifier.
 * @param severity            the rule's effective severity.
 * @param affectedComponents  the components implicated; never null, defensively copied.
 * @param message             the human-readable explanation; non-null.
 * @param context             optional structured detail for tooling; never null, defensively copied.
 */
public record DiagnosticFinding(
        String ruleId,
        Severity severity,
        Set<String> affectedComponents,
        String message,
        Map<String, Object> context) {

    public DiagnosticFinding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        affectedComponents = affectedComponents == null ? Set.of() : Set.copyOf(affectedComponents);
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
