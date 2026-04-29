package eu.inqudium.config.event;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Emitted on the {@code InqRuntime}-scoped publisher when a component is structurally removed
 * from the runtime (ADR-026).
 *
 * <p>Published exactly once per successful removal, after the component has been pulled from its
 * paradigm container's map and its hot phase shut down. A removal vetoed by a listener or by the
 * component-internal mutability check produces a {@code RuntimeComponentVetoedEvent} instead and
 * does not fire this event.
 *
 * <p>Identity fields {@code chainId} and {@code callId} are zero — removals are triggered by
 * {@code runtime.update(...)}, not by an in-flight execute. The {@code elementName} and
 * {@code elementType} identify the removed component.
 */
public final class RuntimeComponentRemovedEvent extends InqEvent {

    /**
     * @param componentName the removed component's name; non-null and non-blank.
     * @param elementType   the component's element type; non-null.
     * @param timestamp     the wall-clock instant of the removal; non-null.
     */
    public RuntimeComponentRemovedEvent(
            String componentName,
            InqElementType elementType,
            Instant timestamp) {
        super(0L, 0L, componentName, elementType, timestamp);
    }
}
