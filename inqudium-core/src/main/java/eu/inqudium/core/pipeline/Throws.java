package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionException;

/**
 * Utility for rethrowing and transporting checked exceptions without wrapping
 * them in {@link RuntimeException}.
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
 * <p>This utility is used by pipeline infrastructure to transport checked exceptions
 * through functional interfaces that do not declare them:</p>
 * <ul>
 *   <li>{@link #wrapChecked(Throwable)} — wraps a checked exception in
 *       {@link CompletionException} for transport through a chain, while letting
 *       unchecked exceptions pass through directly.</li>
 *   <li>{@link #unwrapAndRethrow(CompletionException)} — unwraps the transported
 *       exception at the chain boundary and rethrows the original type.</li>
 *   <li>{@link #rethrow(Throwable)} — the low-level sneaky-throw primitive used
 *       by the other methods.</li>
 * </ul>
 *
 * <h3>Sneaky-throw risk at system boundaries</h3>
 * <p><strong>Warning:</strong> The sneaky-throw mechanism allows checked exceptions
 * to propagate through call sites that do not declare them. Within the pipeline this
 * is intentional — it preserves the proxied method's original exception contract.
 * However, if a sneaky-thrown checked exception escapes beyond the pipeline's caller
 * into infrastructure code that does not expect it, unexpected behavior can occur:</p>
 * <ul>
 *   <li><strong>HTTP frameworks</strong> (Spring MVC, JAX-RS): exception handlers
 *       that filter by {@code RuntimeException} or specific checked types may miss
 *       an undeclared {@code IOException} or {@code SQLException}, causing a raw 500
 *       response or an unhandled-exception log entry.</li>
 *   <li><strong>Thread pools</strong> ({@code ExecutorService}, ForkJoinPool): the
 *       default {@link Thread.UncaughtExceptionHandler} will fire for any uncaught
 *       throwable, but monitoring that filters by type may not recognize a checked
 *       exception escaping from a {@code Runnable}.</li>
 *   <li><strong>Reactive pipelines</strong> (Project Reactor, RxJava): operators
 *       like {@code onErrorResume(IOException.class, ...)} will match, but generic
 *       {@code catch (Exception e)} blocks may behave differently if the actual
 *       type is a checked exception that was not part of the method signature.</li>
 * </ul>
 * <p>To mitigate these risks:</p>
 * <ol>
 *   <li>Ensure that callers of pipeline-advised methods handle {@code Throwable}
 *       (not just {@code Exception}) if the proxied method declares checked
 *       exceptions.</li>
 *   <li>Register a catch-all exception handler at system boundaries (e.g.
 *       {@code @ExceptionHandler(Throwable.class)} in Spring MVC) to prevent
 *       sneaky-thrown checked exceptions from reaching framework internals
 *       unhandled.</li>
 *   <li>Use {@link #wrapChecked(Throwable)} and
 *       {@link #unwrapAndRethrow(CompletionException)} as a matched pair — the
 *       wrapping/unwrapping boundary should be as narrow as possible, ideally
 *       confined to a single method like
 *       {@link eu.inqudium.aspect.pipeline.ResolvedPipeline#execute}.</li>
 * </ol>
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
     * <p><strong>Caution:</strong> A checked exception rethrown via this method
     * will not appear in the calling method's {@code throws} clause. It can
     * propagate to callers that have no {@code catch} block for it. This is
     * intentional within the pipeline (to preserve the proxied method's exception
     * contract), but can cause unexpected behavior if the exception escapes to
     * framework code that does not handle arbitrary checked exceptions.
     * See the class-level Javadoc for mitigation strategies.</p>
     *
     * @param t   the throwable to rethrow (must not be {@code null})
     * @param <E> the inferred exception type (erased at runtime)
     * @return never — declared as {@code RuntimeException} solely for control-flow analysis
     * @throws NullPointerException if {@code t} is {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        if (t == null) {
            throw new NullPointerException("Cannot rethrow null cause");
        }
        throw (E) t;
    }

    /**
     * Wraps a checked exception in {@link CompletionException} for transport through
     * functional interfaces that do not declare checked exceptions.
     *
     * <p>Unchecked exceptions ({@link RuntimeException} and {@link Error}) are rethrown
     * directly — only checked exceptions are wrapped. This preserves the original
     * exception type for unchecked throwables and avoids unnecessary nesting.</p>
     *
     * <p>Typical usage in a terminal executor:</p>
     * <pre>{@code
     * InternalExecutor<Void, Object> terminal = (cid, callId, arg) -> {
     *     try {
     *         return coreExecutor.proceed();
     *     } catch (Throwable t) {
     *         throw Throws.wrapChecked(t);
     *     }
     * };
     * }</pre>
     *
     * @param t the throwable to wrap or rethrow (must not be {@code null})
     * @return never — always throws
     * @throws NullPointerException if {@code t} is {@code null}
     * @throws RuntimeException     if {@code t} is a {@link RuntimeException} (rethrown as-is)
     * @throws Error                if {@code t} is an {@link Error} (rethrown as-is)
     * @throws CompletionException  wrapping {@code t} if it is a checked exception
     * @since 0.7.0
     */
    public static RuntimeException wrapChecked(Throwable t) {
        if (t == null) {
            throw new NullPointerException("Cannot wrap null throwable");
        }
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error err) {
            throw err;
        }
        throw new CompletionException(t);
    }

    /**
     * Unwraps a {@link CompletionException} and rethrows the original cause using
     * the sneaky-throw mechanism.
     *
     * <p>If the {@code CompletionException} has no cause (which should not happen
     * in well-behaved code but is possible), the {@code CompletionException} itself
     * is rethrown to avoid masking the error.</p>
     *
     * <p>This is the counterpart to {@link #wrapChecked(Throwable)} — together they
     * provide transparent checked-exception transport through functional chains:</p>
     * <pre>{@code
     * // Wrap at the terminal:
     * catch (Throwable t) { throw Throws.wrapChecked(t); }
     *
     * // Unwrap at the chain boundary:
     * catch (CompletionException e) { throw Throws.unwrapAndRethrow(e); }
     * }</pre>
     *
     * @param e the completion exception to unwrap
     * @return never — always throws
     * @throws CompletionException if the cause is {@code null} (rethrows {@code e} itself)
     * @since 0.7.0
     */
    public static RuntimeException unwrapAndRethrow(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            throw e;
        }
        throw rethrow(cause);
    }
}
