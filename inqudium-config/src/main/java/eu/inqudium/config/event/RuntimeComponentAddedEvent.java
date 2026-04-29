package eu.inqudium.config.event;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Emitted on the {@code InqRuntime}-scoped publisher when a component is structurally added to
 * the runtime via {@code runtime.update(...)} (ADR-026).
 *
 * <p>Published exactly once per successful materialization, after the new component has been
 * inserted into its paradigm container's map. A name that already existed in the runtime triggers
 * a patch — and a {@code RuntimeComponentPatchedEvent} — instead.
 *
 * <p>Identity fields {@code chainId} and {@code callId} are zero — adds are triggered by
 * {@code runtime.update(...)}, not by an in-flight execute. The {@code elementName} and
 * {@code elementType} identify the newly added component.
 */
public final class RuntimeComponentAddedEvent extends InqEvent {

    /**
     * @param componentName the new component's name; non-null and non-blank.
     * @param elementType   the component's element type; non-null.
     * @param timestamp     the wall-clock instant of the addition; non-null.
     */
    public RuntimeComponentAddedEvent(
            String componentName,
            InqElementType elementType,
            Instant timestamp) {
        super(0L, 0L, componentName, elementType, timestamp);
    }
}
