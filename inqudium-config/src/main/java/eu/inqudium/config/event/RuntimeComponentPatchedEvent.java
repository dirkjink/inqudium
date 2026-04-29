package eu.inqudium.config.event;

import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Emitted on the {@code InqRuntime}-scoped publisher when an existing component's snapshot is
 * mutated via {@code runtime.update(...)} (ADR-026, ADR-028).
 *
 * <p>Published only when the patch produced an effective change to the snapshot, i.e. when the
 * dispatcher reported {@link eu.inqudium.config.validation.ApplyOutcome#PATCHED PATCHED}. A patch
 * whose touched fields all already matched the current values resolves to
 * {@link eu.inqudium.config.validation.ApplyOutcome#UNCHANGED UNCHANGED} and does <em>not</em>
 * fire this event — operational tooling sees only real configuration changes.
 *
 * <p>The {@link #touchedFields()} set lets a subscriber inspect the patch's scope without parsing
 * the {@code BuildReport} or diffing snapshots. Identity fields {@code chainId} and {@code callId}
 * are zero — patches are triggered by {@code runtime.update(...)}, not by an in-flight execute.
 */
public final class RuntimeComponentPatchedEvent extends InqEvent {

    private final Set<? extends ComponentField> touchedFields;

    /**
     * @param componentName the patched component's name; non-null and non-blank.
     * @param elementType   the component's element type; non-null.
     * @param touchedFields the patch's touched fields; non-null. Defensively copied.
     * @param timestamp     the wall-clock instant of the patch; non-null.
     */
    public RuntimeComponentPatchedEvent(
            String componentName,
            InqElementType elementType,
            Set<? extends ComponentField> touchedFields,
            Instant timestamp) {
        super(0L, 0L, componentName, elementType, timestamp);
        this.touchedFields = Set.copyOf(Objects.requireNonNull(touchedFields, "touchedFields"));
    }

    /**
     * @return the immutable set of fields the patch overwrote. Never null, never empty for a
     *         {@code PATCHED} outcome — an empty patch resolves to {@code UNCHANGED} and never
     *         produces this event.
     */
    public Set<? extends ComponentField> touchedFields() {
        return touchedFields;
    }
}
