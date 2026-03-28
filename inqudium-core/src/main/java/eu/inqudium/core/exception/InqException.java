package eu.inqudium.core.exception;

import eu.inqudium.core.InqElementType;

/**
 * Abstract base for all exceptions thrown by Inqudium resilience elements.
 *
 * <p>Inqudium throws its own exceptions <em>only</em> when a resilience element
 * actively intervenes (circuit breaker open, rate limit exhausted, bulkhead full,
 * time limit exceeded, retries exhausted). When the downstream call fails, the
 * original exception propagates unchanged (ADR-009).
 *
 * <p>Every {@code InqException} carries the name and type of the element that
 * threw it, enabling identification without type-level coupling at the catch-site.
 *
 * @since 0.1.0
 */
public abstract class InqException extends RuntimeException {

  private final String elementName;
  private final InqElementType elementType;

  /**
   * Creates a new exception with element context.
   *
   * @param elementName the name of the element instance (e.g. "paymentService")
   * @param elementType the type of the element
   * @param message     the detail message
   */
  protected InqException(String elementName, InqElementType elementType, String message) {
    super(message);
    this.elementName = elementName;
    this.elementType = elementType;
  }

  /**
   * Creates a new exception with element context and a cause.
   *
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @param message     the detail message
   * @param cause       the underlying cause
   */
  protected InqException(String elementName, InqElementType elementType, String message, Throwable cause) {
    super(message, cause);
    this.elementName = elementName;
    this.elementType = elementType;
  }

  /**
   * Returns the name of the element instance that threw this exception.
   *
   * @return the element name, e.g. "paymentService"
   */
  public String getElementName() {
    return elementName;
  }

  /**
   * Returns the type of the element that threw this exception.
   *
   * @return the element type
   */
  public InqElementType getElementType() {
    return elementType;
  }
}
