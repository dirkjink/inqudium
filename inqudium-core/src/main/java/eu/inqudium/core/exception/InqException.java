package eu.inqudium.core.exception;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqElementType;

/**
 * Abstract base for all exceptions thrown by Inqudium resilience elements.
 *
 * <p>Inqudium throws its own exceptions <em>only</em> when a resilience element
 * actively intervenes (circuit breaker open, rate limit exhausted, bulkhead full,
 * time limit exceeded, retries exhausted). When the downstream call fails, the
 * original exception propagates unchanged (ADR-009).
 *
 * <p>Every {@code InqException} carries:
 * <ul>
 *   <li>{@code callId} — the unique call identifier for correlation (ADR-022)</li>
 *   <li>{@code code} — structured error code in {@code INQ-XX-NNN} format (ADR-021)</li>
 *   <li>{@code elementName} — the instance name (e.g. "paymentService")</li>
 *   <li>{@code elementType} — the element kind (e.g. CIRCUIT_BREAKER)</li>
 * </ul>
 *
 * <pre>{@code
 * catch (InqException e) {
 *     log.warn("[{}] {}: {}", e.getCallId(), e.getCode(), e.getMessage());
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
public abstract class InqException extends RuntimeException {

  private final String callId;
  private final String code;
  private final String elementName;
  private final InqElementType elementType;

  /**
   * Creates a new exception with call identity, error code, and element context.
   *
   * @param callId      the unique call identifier, or {@link InqCallIdGenerator#NONE} for standalone use
   * @param code        the structured error code (e.g. "INQ-CB-001")
   * @param elementName the name of the element instance (e.g. "paymentService")
   * @param elementType the type of the element
   * @param message     the detail message (without code/callId prefix — prepended automatically)
   */
  protected InqException(String callId, String code, String elementName,
                         InqElementType elementType, String message) {
    super(formatMessage(callId, code, message));
    this.callId = callId;
    this.code = code;
    this.elementName = elementName;
    this.elementType = elementType;
  }

  /**
   * Creates a new exception with call identity, error code, element context, and a cause.
   *
   * @param callId      the unique call identifier, or {@link InqCallIdGenerator#NONE} for standalone use
   * @param code        the structured error code
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @param message     the detail message (without code/callId prefix — prepended automatically)
   * @param cause       the underlying cause
   */
  protected InqException(String callId, String code, String elementName,
                         InqElementType elementType, String message, Throwable cause) {
    super(formatMessage(callId, code, message), cause);
    this.callId = callId;
    this.code = code;
    this.elementName = elementName;
    this.elementType = elementType;
  }

  private static String formatMessage(String callId, String code, String message) {
    return "[" + callId + "] " + code + ": " + message;
  }

  /**
   * Returns the unique call identifier.
   *
   * <p>All events and exceptions from the same pipeline invocation share
   * this callId (ADR-022). Returns {@link InqCallIdGenerator#NONE} for standalone calls that
   * are not pipeline-correlated.
   *
   * @return the callId, or null
   */
  public String getCallId() {
    return callId;
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
