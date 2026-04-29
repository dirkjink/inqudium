package eu.inqudium.config.event;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEvent;

import java.time.Instant;

/**
 * Emitted exactly once per component when it transitions from {@code COLD} to {@code HOT}.
 *
 * <p>The event travels on the {@code InqRuntime}-scoped publisher (ADR-026), distinct from any
 * per-component publisher in {@code inqudium-core}. Its identity fields ({@code chainId},
 * {@code callId}) describe the call that triggered the transition; the {@code elementName} and
 * {@code elementType} identify the component itself.
 *
 * <p>Operational tooling subscribes to this event to know when each component is "live" — useful
 * for warm-up dashboards and traffic ramp-up health checks. The event is fired exactly once per
 * component lifetime, even under multi-thread CAS contention on the cold-to-hot transition
 * (ADR-029).
 */
public final class ComponentBecameHotEvent extends InqEvent {

    /**
     * @param chainId       the chain identifier of the call that triggered the transition.
     * @param callId        the call identifier of the call that triggered the transition.
     * @param componentName the component's name; non-null and non-blank.
     * @param elementType   the component's element type.
     * @param timestamp     the wall-clock instant of the transition; non-null.
     */
    public ComponentBecameHotEvent(
            long chainId,
            long callId,
            String componentName,
            InqElementType elementType,
            Instant timestamp) {
        super(chainId, callId, componentName, elementType, timestamp);
    }
}
