package eu.inqudium.core.element.fallback;

/**
 * Exception thrown when the fallback handler itself fails to recover
 * from a primary failure.
 */
public class FallbackException extends RuntimeException {

    private final String fallbackName;

    /**
     * Creates a new FallbackException.
     *
     * <p>Fix 7: Guards against null {@code primaryFailure} (which occurs when a result-based
     * fallback handler fails) and against self-suppression (when {@code primaryFailure}
     * and {@code fallbackFailure} are the same object reference, e.g., on a rethrow).
     *
     * @param fallbackName    the name of the fallback provider
     * @param primaryFailure  the original failure that triggered the fallback ({@code null} for result-based fallbacks)
     * @param fallbackFailure the exception thrown by the fallback handler itself
     */
    public FallbackException(String fallbackName, Throwable primaryFailure, Throwable fallbackFailure) {
        super(buildMessage(fallbackName, primaryFailure, fallbackFailure), fallbackFailure);
        this.fallbackName = fallbackName;

        // Fix 7: Only add as suppressed if non-null and not the same object (self-suppression throws)
        if (primaryFailure != null && primaryFailure != fallbackFailure) {
            this.addSuppressed(primaryFailure);
        }
    }

    private static String buildMessage(String fallbackName, Throwable primaryFailure, Throwable fallbackFailure) {
        if (primaryFailure != null) {
            return "FallbackProvider '%s' — fallback handler failed: %s (primary: %s)"
                    .formatted(fallbackName, fallbackFailure.getMessage(), primaryFailure.getMessage());
        }
        return "FallbackProvider '%s' — fallback handler failed: %s"
                .formatted(fallbackName, fallbackFailure.getMessage());
    }

    public String getFallbackName() {
        return fallbackName;
    }
}
