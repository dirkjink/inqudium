package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqElementType;

import java.util.function.Supplier;

/**
 * Decoration contract for resilience elements in a pipeline.
 *
 * <p>Each element that participates in a pipeline implements this interface.
 * The pipeline composes decorators by nesting: the outermost decorator wraps
 * the next, which wraps the next, down to the original supplier (ADR-002).
 *
 * @since 0.1.0
 */
public interface InqDecorator {

  /**
   * Wraps a supplier with this element's resilience logic.
   *
   * @param supplier the supplier to decorate (may be another decorator's output)
   * @param <T>      the result type
   * @return a decorated supplier
   */
  <T> Supplier<T> decorate(Supplier<T> supplier);

  /**
   * Returns the element type for pipeline ordering.
   *
   * @return the element type
   */
  InqElementType getElementType();

  /**
   * Returns the element instance name for diagnostics and event correlation.
   *
   * @return the element name
   */
  String getName();
}
