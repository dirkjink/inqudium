package eu.inqudium.core;

/**
 * Type-safe three-argument invocation.
 *
 * <pre>{@code
 * Invocation3<String, String, Integer, Shipment> resilientShip =
 *     cb.decorateInvocation(shippingService::ship);
 *
 * Shipment s = resilientShip.invoke("warehouse-A", "address-123", 5);
 * }</pre>
 *
 * @param <A1> the first argument type
 * @param <A2> the second argument type
 * @param <A3> the third argument type
 * @param <T>  the result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface Invocation3<A1, A2, A3, T> {

    /**
     * Invokes the operation with the given arguments.
     *
     * @param arg1 the first argument
     * @param arg2 the second argument
     * @param arg3 the third argument
     * @return the result
     * @throws Exception if the operation fails
     */
    T invoke(A1 arg1, A2 arg2, A3 arg3) throws Exception;

    /**
     * Converts this typed invocation to an {@link InvocationArray}.
     *
     * @return the equivalent array invocation
     */
    @SuppressWarnings("unchecked")
    default InvocationArray<T> toArray() {
        return args -> invoke((A1) args[0], (A2) args[1], (A3) args[2]);
    }
}
