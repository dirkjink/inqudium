package eu.inqudium.config.validation;

/**
 * The per-component result of an {@code Inqudium.configure().build()} or
 * {@code runtime.update(...)} call, as carried in {@link BuildReport#componentOutcomes()}.
 *
 * <p>The six values are mutually exclusive per component within a single build/update operation.
 * {@link #VETOED} is distinct from {@link #REJECTED}: a {@code REJECTED} patch is technically
 * wrong (failed validation), while a {@code VETOED} patch is technically correct but disallowed
 * by listener policy or by the component-internal mutability check (ADR-028).
 */
public enum ApplyOutcome {

    /** A new component was created from the patch. */
    ADDED,

    /** An existing component's snapshot was updated. */
    PATCHED,

    /** A component was shut down and removed. */
    REMOVED,

    /** The patch failed class-2 or class-3 validation; see {@code ValidationFinding}s. */
    REJECTED,

    /** The patch was declined by a listener or by the component-internal check; see
     * {@code VetoFinding}s. */
    VETOED,

    /** The patch was a no-op — every touched field already matched the current value. */
    UNCHANGED
}
