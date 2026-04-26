package eu.inqudium.config.patch;

import eu.inqudium.config.snapshot.ComponentSnapshot;

/**
 * The unit of change applied to a {@link ComponentSnapshot}.
 *
 * <p>A patch records which fields the user touched and the new values for those fields. Applying
 * a patch is a per-field choice: touched fields take the new value, untouched fields inherit from
 * the base snapshot. The output is a fresh snapshot that has gone through the same compact
 * constructor (and therefore the same class-2 invariants) as any other snapshot — there is no way
 * for a patch to produce an internally invalid snapshot.
 *
 * <p>Initialization applies a patch to a system-default snapshot and demands completeness. Updates
 * apply a patch to the current snapshot and tolerate partial coverage. Initialization and update
 * differ only in the starting snapshot, not in the application mechanism.
 *
 * @param <S> the component's snapshot type.
 */
public interface ComponentPatch<S extends ComponentSnapshot> {

    /**
     * @param base the snapshot to which untouched fields default.
     * @return a new snapshot with the patched fields replaced and the untouched fields inherited
     *         from {@code base}.
     */
    S applyTo(S base);
}
