package eu.inqudium.core;

/**
 * Varargs convenience interface — syntactic sugar over {@link InvocationArray}.
 *
 * <p>At the JVM level, {@code Object... args} and {@code Object[] args} are
 * identical. This interface exists purely for call-site ergonomics:
 * <pre>{@code
 * // With InvocationArray — explicit array construction
 * result.invoke(new Object[]{"user-1", 42});
 *
 * // With InvocationVarargs — natural varargs syntax
 * result.invoke("user-1", 42);
 * }</pre>
 *
 * <p>Decoration delegates to {@link InvocationArray} via {@link #asArray()}.
 *
 * @param <T> the result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface InvocationVarargs<T> {

    /**
     * Invokes the operation with the given arguments.
     *
     * @param args the arguments (varargs)
     * @return the result
     * @throws Exception if the operation fails
     */
    T invoke(Object... args) throws Exception;

    /**
     * Converts this varargs invocation to an {@link InvocationArray}.
     *
     * <p>Since {@code Object...} is {@code Object[]} at the JVM level,
     * this is a zero-cost conversion.
     *
     * @return the equivalent array invocation
     */
    default InvocationArray<T> asArray() {
        return this::invoke;
    }

    /**
     * Creates a varargs invocation from an {@link InvocationArray}.
     *
     * @param array the array invocation
     * @param <T>   the result type
     * @return the equivalent varargs invocation
     */
    static <T> InvocationVarargs<T> fromArray(InvocationArray<T> array) {
        return array::invoke;
    }
}
