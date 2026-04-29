package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.snapshot.ComponentSnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Default {@link ChangeRequest} implementation handed to listeners and to the component-internal
 * mutability check.
 *
 * <p>Constructed by {@link UpdateDispatcher} on the hot path from the component's current
 * snapshot, the patch's {@link eu.inqudium.config.patch.ComponentPatch#touchedFields() touched
 * fields}, and its {@link eu.inqudium.config.patch.ComponentPatch#proposedValues() proposed
 * values}. Package-private — there is exactly one constructor (the dispatcher) and listeners
 * never need to build their own.
 *
 * <p>{@link #proposedValue(ComponentField, Class)} performs both the membership check (the field
 * must be in {@link #touchedFields()}) and the runtime cast. Membership is enforced because the
 * caller is asking "what is the proposed value for {@code field}", and a field that is not in the
 * touched set has no proposed value — its value is inherited from {@link #currentSnapshot()} on
 * apply. A {@code null} proposal is legal (e.g. an explicit preset clear); the cast skips
 * {@link Class#cast} for {@code null} so the membership check is the single source of truth on
 * "the patch did/did not address this field".
 */
record DefaultChangeRequest<S extends ComponentSnapshot>(
        S currentSnapshot,
        S postPatchSnapshot,
        Set<? extends ComponentField> touchedFields,
        Map<ComponentField, Object> proposedValues) implements ChangeRequest<S> {

    DefaultChangeRequest {
        Objects.requireNonNull(currentSnapshot, "currentSnapshot");
        Objects.requireNonNull(postPatchSnapshot, "postPatchSnapshot");
        Objects.requireNonNull(touchedFields, "touchedFields");
        Objects.requireNonNull(proposedValues, "proposedValues");
        touchedFields = Set.copyOf(touchedFields);
        // proposedValues may legitimately contain null values (e.g. derivedFromPreset cleared via
        // touchDerivedFromPreset(null)); Map.copyOf rejects nulls, so use an unmodifiable copy of
        // a HashMap instead.
        proposedValues = Map.ofEntries(
                proposedValues.entrySet().stream()
                        .map(e -> Map.entry(e.getKey(),
                                e.getValue() == null ? NullSentinel.INSTANCE : e.getValue()))
                        .toArray(Map.Entry[]::new));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T proposedValue(ComponentField field, Class<T> type) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(type, "type");
        if (!touchedFields.contains(field)) {
            throw new IllegalArgumentException(
                    "field '" + field + "' is not in the patch's touched fields; ask "
                            + "currentSnapshot() for its inherited value");
        }
        Object raw = proposedValues.get(field);
        if (raw == NullSentinel.INSTANCE) {
            return null;
        }
        return type.cast(raw);
    }

    @Override
    public Map<ComponentField, Object> allProposedValues() {
        // Replace the null-sentinel back with real null values for caller consumption; listeners
        // expect a normal Map<ComponentField, Object> with possibly-null values.
        Map<ComponentField, Object> view = new java.util.HashMap<>(proposedValues.size() * 2);
        for (Map.Entry<ComponentField, Object> e : proposedValues.entrySet()) {
            view.put(e.getKey(), e.getValue() == NullSentinel.INSTANCE ? null : e.getValue());
        }
        return java.util.Collections.unmodifiableMap(view);
    }

    /**
     * Sentinel value used internally so the underlying immutable map can carry "field touched to
     * null" entries. {@link Map#ofEntries} rejects null values; the sentinel sidesteps that
     * restriction without exposing itself to callers.
     */
    private enum NullSentinel { INSTANCE }
}
