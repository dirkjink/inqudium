package eu.inqudium.core.config;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable record holding the metadata shared by every Inqudium element instance.
 *
 * <h2>Purpose</h2>
 * <p>Captures the four common properties every element needs: a name, an element type,
 * an event publisher, and an exception optimization flag. Higher-level config records
 * (e.g., {@link eu.inqudium.core.element.circuitbreaker.config.InqCircuitBreakerConfig})
 * embed an {@code InqElementCommonConfig} and delegate the {@link InqElementConfig}
 * accessors to it.
 *
 * <h2>Inference</h2>
 * <p>This record overrides {@link ConfigExtension#inference()} to fill in defaults for
 * fields that the user did not explicitly set:
 * <ul>
 *   <li><strong>name:</strong> if {@code null}, an auto-generated name is produced from
 *       the element type and a global atomic counter (e.g., {@code "BULKHEAD-1"}).</li>
 *   <li><strong>eventPublisher:</strong> if {@code null}, a default publisher is created
 *       via {@link InqEventPublisher#create(String, InqElementType)} using the (possibly
 *       inferred) name and element type.</li>
 *   <li><strong>enableExceptionOptimization:</strong> if {@code null}, defaults to {@code true}.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>The auto-naming counter is an {@link AtomicInteger}, making name generation safe
 * for concurrent builder invocations.
 *
 * @param name                        the element name; may be {@code null} before inference
 * @param elementType                 the element type (e.g., CIRCUIT_BREAKER, BULKHEAD); never {@code null}
 * @param eventPublisher              the event publisher; may be {@code null} before inference
 * @param enableExceptionOptimization whether to optimize exception handling; may be {@code null} before inference
 * @see InqElementConfig
 * @see ConfigExtension#inference()
 */
public record InqElementCommonConfig(
    String name,
    InqElementType elementType,
    InqEventPublisher eventPublisher,
    Boolean enableExceptionOptimization
) implements InqElementConfig, ConfigExtension<InqElementCommonConfig> {

  /** Global counter for auto-generating unique element names when none is provided. */
  private final static AtomicInteger counter = new AtomicInteger(1);

  /**
   * Returns a new instance with all {@code null} fields replaced by sensible defaults.
   *
   * <p>Inference order matters: the name is resolved first because the event publisher
   * derivation depends on it.
   *
   * @return a fully populated {@code InqElementCommonConfig}; no field is {@code null}
   */
  @Override
  public InqElementCommonConfig inference() {
    // Infer name first — other defaults depend on it
    String nameInference = name;
    if (name == null) {
      nameInference = elementType.name() + "-" + counter.getAndIncrement();
    }

    // Infer event publisher using the (possibly auto-generated) name
    InqEventPublisher eventPublisherInference = eventPublisher;
    if (eventPublisher == null) {
      eventPublisherInference = InqEventPublisher.create(nameInference, elementType);
    }

    // Default exception optimization to enabled
    Boolean enableExceptionOptimizationInference = enableExceptionOptimization;
    if (enableExceptionOptimization == null) {
      enableExceptionOptimizationInference = true;
    }

    return new InqElementCommonConfig(nameInference,
        elementType,
        eventPublisherInference,
        enableExceptionOptimizationInference);
  }

  @Override
  public InqElementCommonConfig self() {
    return this;
  }
}
