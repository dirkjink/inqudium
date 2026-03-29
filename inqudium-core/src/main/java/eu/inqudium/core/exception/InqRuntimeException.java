package eu.inqudium.core.exception;

import eu.inqudium.core.InqElementType;

import java.util.Locale;

/**
 * Wraps checked exceptions that occur during element execution but are not
 * an active resilience intervention.
 *
 * <p>Extends {@link InqException} so that all Inqudium exceptions share a common
 * base type, error code, and element context. The error code uses the pattern
 * {@code INQ-XX-000} where {@code XX} is the element symbol — the {@code 000}
 * suffix distinguishes wrapped checked exceptions from active interventions
 * (which use {@code 001}, {@code 002}, etc.).
 *
 * <p>This is semantically distinct from other {@link InqException} subclasses:
 * <ul>
 *   <li>{@code InqException} subclasses with codes {@code 001+} = "the element actively intervened"</li>
 *   <li>{@code InqRuntimeException} with code {@code 000} = "the downstream call threw a checked
 *       exception, and the element wrapped it for API convenience"</li>
 * </ul>
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
 * // Catch-site — getCode() returns "INQ-CB-000"
 * catch (InqRuntimeException e) {
 *     log.error("{}: checked exception in '{}': {}",
 *         e.getCode(), e.getElementName(), e.getCause().getMessage());
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
public class InqRuntimeException extends InqException {

    /**
     * Wraps a checked exception with element context.
     *
     * <p>The error code is derived from the element type: {@code INQ-XX-000}.
     *
     * @param elementName the element instance name
     * @param elementType the element type
     * @param cause       the checked exception to wrap
     */
    public InqRuntimeException(String elementName, InqElementType elementType, Throwable cause) {
        super(elementType.errorCode(0), elementName, elementType,
                String.format(Locale.ROOT, "Checked exception in %s '%s': %s",
                        elementType, elementName, cause.getMessage()),
                cause);
    }

    /**
     * Wraps a checked exception without element context.
     *
     * <p>Used by utilities (e.g. {@link InqFailure}) that are not part of an element.
     * The error code is {@code "INQ-SY-000"} (system-level wrapping).
     *
     * @param cause the checked exception to wrap
     */
    public InqRuntimeException(Throwable cause) {
        super("INQ-SY-000", null, null,
                cause.getMessage(),
                cause);
    }

    /**
     * Returns whether this exception carries element context.
     *
     * @return true if element name and type are set
     */
    public boolean hasElementContext() {
        return getElementName() != null && getElementType() != null;
    }
}
