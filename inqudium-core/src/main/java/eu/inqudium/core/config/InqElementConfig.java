package eu.inqudium.core.config;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

/**
 * Common interface for the configuration of any Inqudium element (circuit breaker, bulkhead, etc.).
 *
 * <h2>Purpose</h2>
 * <p>Defines the metadata contract that every element configuration must expose. The
 * framework's runtime infrastructure uses these accessors for logging, metric tagging,
 * event routing, and exception handling optimization — regardless of the concrete
 * element type.
 *
 * <h2>Implementors</h2>
 * <ul>
 *   <li>{@link InqElementCommonConfig} — the canonical record implementation, used as
 *       a building block inside higher-level configs.</li>
 *   <li>{@link eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig}
 *       — delegates to an embedded {@code InqElementCommonConfig}.</li>
 * </ul>
 */
public interface InqElementConfig {

  /**
   * The unique name of this element instance, used for identification in logs,
   * metrics, and events.
   *
   * @return the element name; never {@code null} after inference
   */
  String name();

  /**
   * The type of this element (e.g., CIRCUIT_BREAKER, BULKHEAD), used for
   * categorization in logging and event routing.
   *
   * @return the element type; never {@code null}
   */
  InqElementType elementType();

  /**
   * The event publisher associated with this element, responsible for emitting
   * state-change and lifecycle events.
   *
   * @return the event publisher; never {@code null} after inference
   */
  InqEventPublisher eventPublisher();

  /**
   * Whether exception handling optimization is enabled for this element.
   * When {@code true}, the framework may skip filling in stack traces for
   * internally generated exceptions to reduce overhead.
   *
   * @return {@code true} if optimization is enabled; may be {@code null} before inference
   */
  Boolean enableExceptionOptimization();
}
