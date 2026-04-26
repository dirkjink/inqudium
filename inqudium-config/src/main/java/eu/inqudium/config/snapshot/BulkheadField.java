package eu.inqudium.config.snapshot;

import eu.inqudium.config.lifecycle.ComponentField;

/**
 * The patchable fields of a {@link BulkheadSnapshot}.
 *
 * <p>Used by {@code BulkheadPatch} to track which fields a patch touches, and as the
 * {@link ComponentField} discriminator inside a {@code ChangeRequest&lt;BulkheadSnapshot&gt;}. The
 * {@code derivedFromPreset} field participates in normal touch logic (clarification&nbsp;3 in
 * {@code REFACTORING.md}): preset methods touch it, individual setters do not, and a hot patch
 * that does not call a preset inherits the previous preset label.
 */
public enum BulkheadField implements ComponentField {

    /** The component's name — typically immutable, but exposed as a field for completeness. */
    NAME,

    /** The maximum number of concurrent calls the bulkhead admits. */
    MAX_CONCURRENT_CALLS,

    /** The maximum time a caller waits for a permit before being rejected. */
    MAX_WAIT_DURATION,

    /** The set of operational tags attached to this bulkhead. */
    TAGS,

    /** The label of the preset the snapshot was derived from, or {@code null}. */
    DERIVED_FROM_PRESET
}
