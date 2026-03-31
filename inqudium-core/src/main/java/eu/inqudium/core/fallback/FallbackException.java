package eu.inqudium.core.fallback;

/**
 * Exception thrown when the fallback handler itself fails to recover
 * from a primary failure.
 */
public class FallbackException extends RuntimeException {

  private final String fallbackName;

  public FallbackException(String fallbackName, Throwable primaryFailure, Throwable fallbackFailure) {
    super("FallbackProvider '%s' — fallback handler failed: %s (primary: %s)"
            .formatted(fallbackName, fallbackFailure.getMessage(), primaryFailure.getMessage()),
        fallbackFailure);
    this.addSuppressed(primaryFailure);
    this.fallbackName = fallbackName;
  }

  public String getFallbackName() {
    return fallbackName;
  }
}