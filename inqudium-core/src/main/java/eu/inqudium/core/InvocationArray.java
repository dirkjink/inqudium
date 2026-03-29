package eu.inqudium.core;

/**
 * Base invocation interface for operations with arbitrary arguments.
 *
 * <p>This is the foundational interface — all other invocation types
 * ({@link InvocationVarargs}, {@link Invocation}, {@link Invocation2},
 * {@link Invocation3}) can delegate to it via conversion methods.
 *
 * <p>The decoration in {@link eu.inqudium.core.pipeline.InqDecorator} is
 * fully implemented for this type. All other invocation types delegate
 * their decoration to {@code InvocationArray}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Wrap an existing method
 * InvocationArray<String> lookup = args ->
 *     repository.findBy((String) args[0], (Integer) args[1]);
 *
 * // Decorate once
 * InvocationArray<String> resilient = cb.decorateInvocation(lookup);
 *
 * // Call with different arguments each time
 * resilient.invoke(new Object[]{"user-1", 42});
 * resilient.invoke(new Object[]{"user-2", 99});
 * }</pre>
 *
 * @param <T> the result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface InvocationArray<T> {

  /**
   * Invokes the operation with the given arguments.
   *
   * @param args the arguments
   * @return the result
   * @throws Exception if the operation fails
   */
  T invoke(Object[] args) throws Exception;
}
