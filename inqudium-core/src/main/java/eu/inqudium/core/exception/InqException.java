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
 * <p>Every {@code InqException} carries a structured error code (ADR-021), the name
 * and type of the element that threw it, enabling identification without type-level
 * coupling at the catch-site:
 * <pre>{@code
 * catch (InqException e) {
 *     metrics.increment("inqudium.error." + e.getCode());
 *     log.warn("{}: {}", e.getCode(), e.getMessage());
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
public abstract class InqException extends RuntimeException {

  private final String code;
  private final String elementName;
  private final InqElementType elementType;

  /**
   * Creates a new exception with error code and element context.
   *
   * @param code        the structured error code (e.g. "INQ-CB-001")
   * @param elementName the name of the element instance (e.g. "paymentService")
   * @param elementType the type of the element
   * @param message     the detail message (without code prefix — prepended automatically)
   */
  protected InqException(String code, String elementName, InqElementType elementType, String message) {
    super(code + ": " + message);
    this.code = code;
    this.elementName = elementName;
    this.elementType = elementType;
  }

  /**
   * Creates a new exception with error code, element context, and a cause.
   *
   * @param code        the structured error code
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @param message     the detail message (without code prefix — prepended automatically)
   * @param cause       the underlying cause
   */
  protected InqException(String code, String elementName, InqElementType elementType, String message, Throwable cause) {
    super(code + ": " + message, cause);
    this.code = code;
    this.elementName = elementName;
    this.elementType = elementType;
  }

  /**
   * Returns the structured error code.
   *
   * <p>Codes follow the format {@code INQ-XX-NNN} where {@code XX} is the
   * two-character element symbol and {@code NNN} is a three-digit number.
   * Codes are stable across minor versions (ADR-021).
   *
   * @return the error code, e.g. "INQ-CB-001"
   */
  public String getCode() {
    return code;
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
