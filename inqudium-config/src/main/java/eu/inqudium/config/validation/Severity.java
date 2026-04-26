package eu.inqudium.config.validation;

/**
 * The severity of a {@link ValidationFinding} or
 * {@link DiagnosticFinding}.
 *
 * <p>Coarse-grained on purpose. ADR-027 rejected a finer scale (CRITICAL/MAJOR/MINOR) as adding
 * ceremony without practical benefit; finer distinctions are made via stable rule IDs and
 * per-rule severity overrides.
 */
public enum Severity {

    /** Aborts the build (or, in collected mode, contributes to a final failure). */
    ERROR,

    /** Reported but does not abort the build. Strict mode elevates warnings to errors. */
    WARNING,

    /** Purely informational; never aborts. */
    INFO
}
