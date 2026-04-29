package eu.inqudium.config.patch;

import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.snapshot.ComponentSnapshot;

import java.util.Map;
import java.util.Set;

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
 * <p>The {@link #touchedFields()} and {@link #proposedValues()} accessors expose the patch's
 * intent generically, without forcing the caller to know the concrete patch type. The phase-2
 * dispatcher uses them to construct the {@link eu.inqudium.config.lifecycle.ChangeRequest
 * ChangeRequest} handed to listeners and to the component-internal mutability check.
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

    /**
     * @return an immutable view of the fields this patch will overwrite when applied. Untouched
     *         fields are not in the set. The returned set's element type is the component-specific
     *         {@link ComponentField} enum.
     */
    Set<? extends ComponentField> touchedFields();

    /**
     * @return an immutable map of proposed values keyed by field. Only the fields in
     *         {@link #touchedFields()} appear in the map; untouched fields are absent (callers
     *         must not infer "no change" from a missing key — a key may simply have been left at
     *         the base snapshot's value). A value may be {@code null} if the patch's
     *         {@code touchXxx} method was called with {@code null} (e.g. clearing an optional
     *         field); the snapshot's compact constructor decides whether {@code null} is legal at
     *         apply time.
     */
    Map<ComponentField, Object> proposedValues();
}
