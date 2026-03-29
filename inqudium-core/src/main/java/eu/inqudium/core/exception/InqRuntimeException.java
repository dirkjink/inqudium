package eu.inqudium.core.exception;

import eu.inqudium.core.InqElementType;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

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
     * <p>The error code is derived from the element type: {@code INQ-XX-000}.
     *
     * @param callId      the unique call identifier
     * @param elementName the element instance name
     * @param elementType the element type
     * @param cause       the checked exception to wrap
     */
    public InqRuntimeException(String callId, String elementName, InqElementType elementType, Throwable cause) {
        super(callId,
                elementType != null ? elementType.errorCode(0) : "INQ-SY-000",
                elementName, elementType,
                elementType != null
                        ? String.format(Locale.ROOT, "Checked exception in %s '%s': %s",
                                elementType, elementName, cause.getMessage())
                        : String.format(Locale.ROOT, "Checked exception in '%s': %s",
                                elementName, cause.getMessage()),
                cause);
    }

    /**
     * Wraps a checked exception without element context.
     *
     * <p>Package-private — used only by {@link InqFailure} which lives in the same package.
     * The error code is {@code "INQ-SY-000"} (system-level wrapping).
     *
     * @param cause the checked exception to wrap
     */
    InqRuntimeException(Throwable cause) {
        super(null, "INQ-SY-000", null, null,
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

    /**
     * Converts a {@link Callable} to a {@link Supplier}, wrapping checked exceptions
     * in {@code InqRuntimeException} with the given call identity and element context.
     *
     * @param callable    the callable to wrap
     * @param callId      the unique call identifier
     * @param elementName the element instance name (for error context)
     * @param elementType the element type (for error code derivation)
     * @param <T>         the result type
     * @return a supplier that invokes the callable and wraps checked exceptions
     */
    public static <T> Supplier<T> wrapCallable(Callable<T> callable,
                                                String callId,
                                                String elementName,
                                                InqElementType elementType) {
        return () -> {
            try {
                return callable.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new InqRuntimeException(callId, elementName, elementType, e);
            }
        };
    }
}
