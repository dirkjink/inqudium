package eu.inqudium.core.exception;

import eu.inqudium.core.InqCallIdGenerator;
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
 *     throw new InqRuntimeException(callId, elementName, elementType, e);
 * }
 *
 * // Catch-site — getCode() returns "INQ-CB-000", getCallId() returns the call identity
 * catch (InqRuntimeException e) {
 *     log.error("[{}] {}: checked exception in '{}': {}",
 *         e.getCallId(), e.getCode(), e.getElementName(), e.getCause().getMessage());
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
public class InqRuntimeException extends InqException {

    /**
     * Wraps a checked exception with call identity and element context.
     *
     * <p>The cause is {@linkplain InqFailure#unwrap(Throwable) unwrapped} to strip
     * common wrapper exceptions (ExecutionException, CompletionException, etc.)
     * before storing. The error code is derived from the element type: {@code INQ-XX-000}.
     *
     * @param callId      the unique call identifier
     * @param elementName the element instance name
     * @param elementType the element type
     * @param cause       the checked exception to wrap (unwrapped automatically)
     */
    public InqRuntimeException(String callId, String elementName, InqElementType elementType, Throwable cause) {
        super(callId,
                (elementType != null ? elementType : InqElementType.NO_ELEMENT).errorCode(0),
                elementName,
                elementType != null ? elementType : InqElementType.NO_ELEMENT,
                formatCauseMessage(elementName, elementType, InqFailure.unwrap(cause)),
                InqFailure.unwrap(cause));
    }

    private static String formatCauseMessage(String elementName, InqElementType elementType, Throwable unwrapped) {
        if (elementType != null && elementType != InqElementType.NO_ELEMENT) {
            return String.format(Locale.ROOT, "Checked exception in %s '%s': %s",
                    elementType, elementName, unwrapped.getMessage());
        }
        if (elementName != null) {
            return String.format(Locale.ROOT, "Checked exception in '%s': %s",
                    elementName, unwrapped.getMessage());
        }
        return unwrapped.getMessage();
    }

    /**
     * Wraps a checked exception without element context.
     *
     * <p>Package-private — used only by {@link InqFailure} which lives in the same package.
     * The cause is {@linkplain InqFailure#unwrap(Throwable) unwrapped} before storing.
     * The error code is {@code "INQ-XX-000"} (no element context).
     *
     * @param cause the checked exception to wrap (unwrapped automatically)
     */
    InqRuntimeException(Throwable cause) {
        super(InqCallIdGenerator.NONE, InqElementType.NO_ELEMENT.errorCode(0),
                null, InqElementType.NO_ELEMENT,
                formatCauseMessage(null, null, InqFailure.unwrap(cause)),
                InqFailure.unwrap(cause));
    }

    /**
     * Returns whether this exception carries element context.
     *
     * @return true if element name is set and element type is not {@link InqElementType#NO_ELEMENT}
     */
    public boolean hasElementContext() {
        return getElementName() != null && getElementType() != InqElementType.NO_ELEMENT;
    }
}
