package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;

import java.time.Instant;
import java.util.Objects;

/**
 * Abstract base for all events emitted by Inqudium resilience elements.
 *
 * <p>Every event carries four fields for correlation and identification (ADR-003):
 * <ul>
 *   <li>{@code callId} — unique identifier for the call, shared across all elements in a pipeline</li>
 *   <li>{@code elementName} — the named instance that emitted this event</li>
 *   <li>{@code elementType} — which element kind emitted this event</li>
 *   <li>{@code timestamp} — when the event occurred</li>
 * </ul>
 *
 * <p>Element-specific subclasses add context: {@code fromState}/{@code toState} for
 * circuit breaker transitions, {@code attemptNumber}/{@code waitDuration} for retries, etc.
 *
 * @since 0.1.0
 */
public abstract class InqEvent {

  private final String callId;
  private final String elementName;
  private final InqElementType elementType;
  private final Instant timestamp;

  /**
   * Creates a new event with the given identity fields.
   *
   * @param callId      the unique call identifier (shared across the pipeline)
   * @param elementName the name of the element instance
   * @param elementType the type of the element
   * @param timestamp   when the event occurred
   */
  protected InqEvent(String callId, String elementName, InqElementType elementType, Instant timestamp) {
    this.callId = Objects.requireNonNull(callId, "callId must not be null");
    this.elementName = Objects.requireNonNull(elementName, "elementName must not be null");
    this.elementType = Objects.requireNonNull(elementType, "elementType must not be null");
    this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  /**
   * Returns the unique call identifier shared across all elements in the pipeline.
   */
  public String getCallId() {
    return callId;
  }

  /**
   * Returns the name of the element instance that emitted this event.
   */
  public String getElementName() {
    return elementName;
  }

  /**
   * Returns the type of the element that emitted this event.
   */
  public InqElementType getElementType() {
    return elementType;
  }

  /**
   * Returns the timestamp when this event occurred.
   */
  public Instant getTimestamp() {
    return timestamp;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "callId='" + callId + '\'' +
        ", elementName='" + elementName + '\'' +
        ", elementType=" + elementType +
        ", timestamp=" + timestamp +
        '}';
  }
}
