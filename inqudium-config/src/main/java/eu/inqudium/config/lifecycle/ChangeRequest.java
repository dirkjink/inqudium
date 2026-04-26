package eu.inqudium.config.lifecycle;

import eu.inqudium.config.snapshot.ComponentSnapshot;

import java.util.Map;
import java.util.Set;

/**
 * The patch-application request handed to listeners and the component-internal mutability check.
 *
 * <p>A request exposes the component's current snapshot, the set of fields the patch wants to
 * change, and the proposed values, all typed against the component's snapshot type {@code S}. The
 * request itself does not apply the patch — listeners only inspect and decide; the dispatcher
 * applies on full acceptance.
 *
 * @param <S> the component's snapshot type.
 */
public interface ChangeRequest<S extends ComponentSnapshot> {

    /**
     * @return the component's current snapshot, captured at the start of patch dispatch.
     */
    S currentSnapshot();

    /**
     * @return the set of fields the patch intends to modify. The set's element type is the
     *         component-specific {@link ComponentField} enum (e.g. {@code BulkheadField}).
     */
    Set<? extends ComponentField> touchedFields();

    /**
     * @param field the field to inspect; must be in {@link #touchedFields()}.
     * @param type  the expected runtime type of the value.
     * @param <T>   the value's compile-time type.
     * @return the proposed new value for {@code field}.
     * @throws ClassCastException       if the value is not an instance of {@code type}.
     * @throws IllegalArgumentException if {@code field} is not in {@link #touchedFields()}.
     */
    <T> T proposedValue(ComponentField field, Class<T> type);

    /**
     * @return all proposed values keyed by their fields. Convenience for listeners that want to
     *         dump the full patch in a single map.
     */
    Map<ComponentField, Object> allProposedValues();
}
