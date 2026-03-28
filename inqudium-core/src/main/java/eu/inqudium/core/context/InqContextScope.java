package eu.inqudium.core.context;

/**
 * Scope handle returned by {@link InqContextPropagator#restore(InqContextSnapshot)}.
 *
 * <p>Must be used in a try-with-resources block to ensure the previous context
 * is restored after the protected call completes:
 * <pre>{@code
 * try (var scope = propagator.restore(snapshot)) {
 *     propagator.enrich(callId, elementName, elementType);
 *     return protectedCall.execute();
 * } // scope.close() restores the previous context
 * }</pre>
 *
 * @since 0.1.0
 */
public interface InqContextScope extends AutoCloseable {

  /**
   * A no-op scope for cases where no propagators are registered.
   */
  InqContextScope NOOP = () -> {
  };

  /**
   * Restores the previous context.
   *
   * <p>Does not throw checked exceptions — safe for use in finally blocks
   * and try-with-resources.
   */
  @Override
  void close();
}
