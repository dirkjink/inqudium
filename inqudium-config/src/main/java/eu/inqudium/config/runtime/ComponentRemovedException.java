package eu.inqudium.config.runtime;

import eu.inqudium.core.element.InqElementType;

import java.util.Objects;

/**
 * Thrown when an operation is invoked on a component handle that was structurally removed from
 * the runtime (ADR-026).
 *
 * <p>External handle references obtained before the removal — for example via
 * {@link Imperative#bulkhead(String)} or
 * {@link Imperative#findBulkhead(String) findBulkhead} — survive the removal but go inert.
 * Subsequent {@code execute(...)}, {@code snapshot()}, {@code availablePermits()}, and
 * {@code concurrentCalls()} calls on such a handle raise this exception. Listener-registration
 * methods do not throw — listeners on a removed handle are silently retained on the inert handle
 * until the handle itself is garbage-collected.
 *
 * <p>The component identity ({@link #componentName()}, {@link #elementType()}) is captured so
 * operators reading the stack trace can immediately tell which component was hit.
 */
public final class ComponentRemovedException extends RuntimeException {

    private final String componentName;
    private final InqElementType elementType;

    /**
     * @param componentName the name of the removed component; non-null.
     * @param elementType   the element type of the removed component; non-null.
     */
    public ComponentRemovedException(String componentName, InqElementType elementType) {
        super("component '" + Objects.requireNonNull(componentName, "componentName")
                + "' (" + Objects.requireNonNull(elementType, "elementType")
                + ") has been removed from the runtime");
        this.componentName = componentName;
        this.elementType = elementType;
    }

    /**
     * @return the name of the removed component.
     */
    public String componentName() {
        return componentName;
    }

    /**
     * @return the element type of the removed component.
     */
    public InqElementType elementType() {
        return elementType;
    }
}
