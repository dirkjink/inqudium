package eu.inqudium.core.pipeline;

/**
 * Utility for rethrowing checked exceptions without wrapping them.
 *
 * <p>This class uses a well-known "sneaky throw" technique that exploits Java's
 * type erasure to bypass the compiler's checked-exception rules at the bytecode
 * level while remaining fully legal Java source code.</p>
 *
 * <h3>How the trick works</h3>
 * <p>The method signature declares {@code throws E} where {@code E extends Throwable}.
 * At compile time, the caller sees a method that throws some inferred type. At runtime,
 * type erasure removes the generic bound — the cast {@code (E) t} becomes a no-op
 * {@code (Throwable) t} cast, and the exception is thrown as-is, bypassing the compiler's
 * checked-exception rules.</p>
 *
 * <h3>Why the method returns {@code RuntimeException}</h3>
 * <p>The internal {@code throw (E) t} is the actual mechanism — it cannot be replaced
 * by a {@code return}. The return type {@code RuntimeException} is a fiction: the method
 * never actually returns. It exists solely so callers can write:</p>
 * <pre>{@code
 * throw Throws.rethrow(cause);   // tells the compiler: control flow ends here
 * }</pre>
 * <p>Without the outer {@code throw}, the compiler would complain about missing return
 * statements or reachable code after the call. The outer {@code throw} is never
 * actually executed — the inner throw fires first.</p>
 *
 * <h3>Usage in the framework</h3>
 * <p>This utility is used primarily by {@link eu.inqudium.core.pipeline.proxy.DispatchExtension}
 * to rethrow declared checked exceptions from proxied method calls without wrapping
 * them in {@link RuntimeException} or {@link java.lang.reflect.UndeclaredThrowableException}.</p>
 *
 * @since 0.5.0
 */
public final class Throws {

    /**
     * Prevent instantiation — this is a utility class.
     */
    private Throws() {
    }

    /**
     * Rethrows any {@link Throwable} without wrapping, bypassing checked-exception rules.
     *
     * <p>Usage pattern: {@code throw Throws.rethrow(cause);}</p>
     *
     * <p>The outer {@code throw} keyword is never executed — the internal
     * {@code throw (E) t} fires first via the sneaky-throw mechanism. The
     * outer {@code throw} serves only as a compiler hint that control flow
     * terminates here.</p>
     *
     * @param t   the throwable to rethrow (must not be {@code null})
     * @param <E> the inferred exception type (erased at runtime)
     * @return never — declared as {@code RuntimeException} solely for control-flow analysis
     * @throws NullPointerException if {@code t} is {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        // Null guard: rethrowing null would produce a confusing NullPointerException
        // deep in the call stack — fail fast with a clear message instead
        if (t == null) {
            throw new NullPointerException("Cannot rethrow null cause");
        }
        // The cast to E is erased at runtime — this effectively becomes
        // "throw (Throwable) t", which the JVM executes without any
        // checked-exception verification. The compiler sees "throws E" and
        // infers the type from context, but at bytecode level no wrapping occurs.
        throw (E) t;
    }
}
