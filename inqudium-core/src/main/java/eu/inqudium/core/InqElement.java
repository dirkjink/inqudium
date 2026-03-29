package eu.inqudium.core;

import eu.inqudium.core.event.InqEventPublisher;

/**
 * Base interface implemented by all resilience elements across all paradigms.
 *
 * <p>Every element has a name (used for registry lookup and event correlation),
 * a type (used for pipeline ordering and event identification), and an event
 * publisher (used for observability).
 *
 * @since 0.1.0
 */
public interface InqElement {

  /**
   * Returns the name of this element instance.
   *
   * <p>The name is unique within a registry (ADR-015) and appears in all
   * events (ADR-003) and exceptions (ADR-009) emitted by this element.
   *
   * @return the instance name, e.g. "paymentService"
   */
  String getName();

  /**
   * Returns the element type.
   *
   * @return the element kind
   */
  InqElementType getElementType();

  /**
   * Returns the event publisher for this element instance.
   *
   * <p>Consumers subscribe via this publisher to receive events from this
   * specific element (ADR-003).
   *
   * @return the per-instance event publisher
   */
  InqEventPublisher getEventPublisher();
}
