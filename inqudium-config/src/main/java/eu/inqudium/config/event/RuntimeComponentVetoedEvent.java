package eu.inqudium.config.event;

import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * Emitted on the {@code InqRuntime}-scoped publisher when a hot patch or a hot removal is
 * rejected — either by a registered listener or by the component-internal mutability check
 * (ADR-026, ADR-028).
 *
 * <p>Published exactly once per veto, mirroring the {@link VetoFinding} that lands in the
 * {@code BuildReport}. Subscribers can use this event to drive alerts ("policy rejected a change")
 * without polling {@code BuildReport}s. The carried {@link VetoFinding} exposes the reason, the
 * source ({@link VetoFinding.Source#LISTENER LISTENER} vs.
 * {@link VetoFinding.Source#COMPONENT_INTERNAL COMPONENT_INTERNAL}), and the touched fields if
 * the veto was on a patch — for a vetoed removal the touched-field set is empty.
 *
 * <p>Identity fields {@code chainId} and {@code callId} are zero — vetoes are triggered by
 * {@code runtime.update(...)}, not by an in-flight execute. The {@code elementName} mirrors
 * {@code vetoFinding.componentKey().name()}; the {@code elementType} is supplied separately
 * because the dispatcher's {@link VetoFinding} is paradigm-agnostic and does not carry it.
 */
public final class RuntimeComponentVetoedEvent extends InqEvent {

    private final VetoFinding vetoFinding;

    /**
     * @param componentName the vetoed component's name; non-null and non-blank. Must equal
     *                      {@code vetoFinding.componentKey().name()}.
     * @param elementType   the component's element type; non-null.
     * @param vetoFinding   the finding that drove the rejection; non-null.
     * @param timestamp     the wall-clock instant of the veto; non-null.
     */
    public RuntimeComponentVetoedEvent(
            String componentName,
            InqElementType elementType,
            VetoFinding vetoFinding,
            Instant timestamp) {
        super(0L, 0L, componentName, elementType, timestamp);
        this.vetoFinding = Objects.requireNonNull(vetoFinding, "vetoFinding");
        if (!componentName.equals(vetoFinding.componentKey().name())) {
            throw new IllegalArgumentException(
                    "componentName '" + componentName + "' does not match "
                            + "vetoFinding.componentKey().name() '"
                            + vetoFinding.componentKey().name() + "'");
        }
    }

    /**
     * @return the veto finding describing reason, source, and touched fields. Never null.
     */
    public VetoFinding vetoFinding() {
        return vetoFinding;
    }
}
