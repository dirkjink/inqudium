package eu.inqudium.core.exception;

import eu.inqudium.core.InqElementType;

/**
 * Wraps checked exceptions that occur during element execution but are not
 * an active resilience intervention.
 *
 * <p>This is distinct from {@link InqException}: an {@code InqException} signals
 * that an element actively intervened (circuit breaker opened, retries exhausted).
 * An {@code InqRuntimeException} signals that the downstream call threw a checked
 * exception which was converted to an unchecked exception for API convenience
 * (e.g. {@code Callable.call()} throwing {@code Exception}).
 *
 * <p>Carries element context (name, type) when the wrapping occurs inside an element,
 * so the catch-site knows which element was involved. When used outside an element
 * (e.g. in {@link InqFailure}), element context is null.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Inside an element — wrapping a checked exception from Callable
 * try {
 *     return callable.call();
 * } catch (RuntimeException re) {
 *     throw re;
 * } catch (Exception e) {
 *     throw new InqRuntimeException(elementName, elementType, e);
 * }
 *
 * // Catch-site
 * catch (InqRuntimeException e) {
 *     log.error("Checked exception in element '{}': {}", e.getElementName(), e.getCause());
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
public class InqRuntimeException extends RuntimeException {

    private final String elementName;
    private final InqElementType elementType;

    /**
     * Wraps a checked exception with element context.
     *
     * @param elementName the element instance name (may be null if outside an element)
     * @param elementType the element type (may be null if outside an element)
     * @param cause       the checked exception to wrap
     */
    public InqRuntimeException(String elementName, InqElementType elementType, Throwable cause) {
        super(buildMessage(elementName, elementType, cause), cause);
        this.elementName = elementName;
        this.elementType = elementType;
    }

    /**
     * Wraps a checked exception without element context.
     *
     * <p>Used by utilities (e.g. {@link InqFailure}) that are not part of an element.
     *
     * @param cause the checked exception to wrap
     */
    public InqRuntimeException(Throwable cause) {
        super(cause.getMessage(), cause);
        this.elementName = null;
        this.elementType = null;
    }

    /**
     * Returns the element instance name, or null if the wrapping occurred
     * outside an element.
     *
     * @return the element name, or null
     */
    public String getElementName() {
        return elementName;
    }

    /**
     * Returns the element type, or null if the wrapping occurred outside
     * an element.
     *
     * @return the element type, or null
     */
    public InqElementType getElementType() {
        return elementType;
    }

    /**
     * Returns whether this exception carries element context.
     *
     * @return true if element name and type are set
     */
    public boolean hasElementContext() {
        return elementName != null && elementType != null;
    }

    private static String buildMessage(String elementName, InqElementType elementType, Throwable cause) {
        if (elementName != null && elementType != null) {
            return String.format(java.util.Locale.ROOT,
                    "Checked exception in %s '%s': %s",
                    elementType, elementName, cause.getMessage());
        }
        return cause.getMessage();
    }
}
