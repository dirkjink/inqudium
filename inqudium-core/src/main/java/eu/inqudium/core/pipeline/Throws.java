package eu.inqudium.core.pipeline;

/**
 * Utility for rethrowing checked exceptions without wrapping them.
 *
 * <h3>How the trick works</h3>
 * <p>The method signature declares {@code throws E} where {@code E extends Throwable}.
 * At compile time, the caller sees a method that throws some inferred type. At runtime,
 * type erasure removes the generic bound — the cast {@code (E) t} becomes a no-op
 * {@code (Throwable) t} cast, and the exception is thrown as-is, bypassing the compiler's
 * checked-exception rules.</p>
 *
 * <h3>Why the method throws internally</h3>
 * <p>The internal {@code throw (E) t} is the actual mechanism — it cannot be replaced
 * by a {@code return}. The return type {@code RuntimeException} is a fiction: the method
 * never returns. It exists solely so callers can write:</p>
 * <pre>{@code
 * throw Throws.rethrow(cause);   // tells the compiler: control flow ends here
 * }</pre>
 * <p>Without the outer {@code throw}, the compiler would complain about missing return
 * statements or reachable code after the call.</p>
 *
 * @since 0.5.0
 */
public final class Throws {

  private Throws() {
  }

  /**
   * Rethrows any {@link Throwable} without wrapping, bypassing checked-exception rules.
   *
   * <p>Usage: {@code throw Throws.rethrow(cause);}</p>
   *
   * <p>The outer {@code throw} is never executed — it is a compiler hint.
   * The inner throw does the actual work via type-erasure.</p>
   *
   * @param t the throwable to rethrow (must not be {@code null})
   * @return never — declared as {@code RuntimeException} for control-flow analysis
   * @throws NullPointerException if {@code t} is {@code null}
   */
  @SuppressWarnings("unchecked")
  public static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
    if (t == null) {
      throw new NullPointerException("Cannot rethrow null cause");
    }
    throw (E) t;
  }
}
