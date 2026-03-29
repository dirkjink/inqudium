package eu.inqudium.core;

/**
 * Type-safe single-argument invocation — the most common case.
 *
 * <p>This is an optimization over {@link InvocationArray}: no array allocation,
 * full type safety on both argument and return type. Use this whenever the
 * downstream operation takes exactly one argument:
 * <pre>{@code
 * // Decorate once — type-safe on argument and return type
 * Invocation<String, Payment> resilientCharge =
 *     cb.decorateInvocation(paymentService::charge);
 *
 * // Call with different arguments at runtime
 * Payment p1 = resilientCharge.invoke("order-1");
 * Payment p2 = resilientCharge.invoke("order-2");
 * }</pre>
 *
 * @param <A> the argument type
 * @param <T> the result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface Invocation<A, T> {

  /**
   * Invokes the operation with the given argument.
   *
   * @param arg the argument
   * @return the result
   * @throws Exception if the operation fails
   */
  T invoke(A arg) throws Exception;

  /**
   * Converts this typed invocation to an {@link InvocationArray}.
   *
   * <p>The single argument is extracted from {@code args[0]} with an
   * unchecked cast. Used internally by decoration to delegate to the
   * {@code InvocationArray} path when needed.
   *
   * @return the equivalent array invocation
   */
  @SuppressWarnings("unchecked")
  default InvocationArray<T> toArray() {
    return args -> invoke((A) args[0]);
  }
}
