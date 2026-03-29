package eu.inqudium.core;

/**
 * Type-safe two-argument invocation.
 *
 * <pre>{@code
 * Invocation2<String, Integer, Order> resilientOrder =
 *     cb.decorateInvocation(orderService::placeOrder);
 *
 * Order o1 = resilientOrder.invoke("SKU-100", 3);
 * Order o2 = resilientOrder.invoke("SKU-200", 1);
 * }</pre>
 *
 * @param <A1> the first argument type
 * @param <A2> the second argument type
 * @param <T>  the result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface Invocation2<A1, A2, T> {

  /**
   * Invokes the operation with the given arguments.
   *
   * @param arg1 the first argument
   * @param arg2 the second argument
   * @return the result
   * @throws Exception if the operation fails
   */
  T invoke(A1 arg1, A2 arg2) throws Exception;

  /**
   * Converts this typed invocation to an {@link InvocationArray}.
   *
   * @return the equivalent array invocation
   */
  @SuppressWarnings("unchecked")
  default InvocationArray<T> toArray() {
    return args -> invoke((A1) args[0], (A2) args[1]);
  }
}
