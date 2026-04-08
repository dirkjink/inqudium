package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.Throws;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;

/**
 * SPI (Service Provider Interface) for pluggable dispatch strategies inside a
 * {@link ProxyWrapper}.
 *
 * <p>Each {@code DispatchExtension} represents a specific dispatch mode — for example,
 * synchronous invocation, asynchronous invocation, or reactive stream handling.
 * Extensions are registered on a {@link ProxyWrapper} and checked in registration
 * order; the first whose {@link #canHandle} returns {@code true} receives the call.</p>
 *
 * <h3>Extension ordering contract</h3>
 * <ul>
 *   <li>More specific extensions (e.g. async-only) must be registered before
 *       less specific ones.</li>
 *   <li>Exactly one catch-all extension (where {@link #isCatchAll()} returns {@code true})
 *       must be registered <strong>last</strong>. This guarantees that every method can
 *       be dispatched.</li>
 *   <li>{@link ProxyWrapper#createProxy} validates these rules at construction time,
 *       failing fast if any are violated.</li>
 * </ul>
 *
 * <h3>Chain-walk linking</h3>
 * <p>When proxies are stacked (a proxy wrapping another proxy), the framework calls
 * {@link #linkInner} on each outer extension, passing the inner proxy's extensions.
 * This allows type-compatible extensions to wire a direct chain walk that bypasses
 * proxy re-entry, significantly reducing dispatch overhead for deep chains.</p>
 *
 * @since 0.5.0
 */
public interface DispatchExtension {

  /**
   * Returns {@code true} if this extension can dispatch the given method.
   *
   * <p>Implementations typically check the method's return type, annotations,
   * or declaring interface to decide. For example, an async extension might
   * check if the return type is {@code CompletableFuture}.</p>
   *
   * @param method the service method being invoked
   * @return {@code true} if this extension should handle the method
   */
  boolean canHandle(Method method);

  /**
   * Dispatches the method call through this extension's logic.
   *
   * <p>The implementation should apply any cross-cutting concerns (timing,
   * resilience, etc.) and eventually invoke the method on the {@code target}
   * object. The target is either the inner JDK proxy (for unlinked extensions)
   * or the deep real target (for linked extensions that use chain-walk
   * optimization).</p>
   *
   * @param chainId the chain identifier — shared across all layers in the chain,
   *                useful for log correlation
   * @param callId  the per-call identifier — unique per invocation, useful for
   *                tracing individual calls through the chain
   * @param method  the service method being invoked
   * @param args    the method arguments (may be {@code null} for zero-arg methods)
   * @param target  the invocation target for the terminal step — either the inner
   *                proxy or the deep real target depending on linking
   * @return the method's return value
   * @throws Throwable if the underlying method or dispatch logic throws
   */
  Object dispatch(long chainId, long callId,
                  Method method, Object[] args, Object target) throws Throwable;

  /**
   * Links this extension with matching inner extensions for chain-walk optimization.
   *
   * <p>Called during proxy construction when the delegate is itself a proxy.
   * The framework passes the inner proxy's extension array and the deep real target.
   * Implementations should:</p>
   * <ol>
   *   <li>Search {@code innerExtensions} for a type-compatible counterpart
   *       (e.g. another {@code SyncDispatchExtension}).</li>
   *   <li>If found: return a new instance that chains directly into the inner
   *       extension, using {@code realTarget} as the terminal invocation target.
   *       This creates a "chain walk" that bypasses proxy re-entry.</li>
   *   <li>If not found: return themselves unchanged. The framework will pass the
   *       delegate proxy as the invocation target at dispatch time, ensuring that
   *       the inner proxy's extensions are still invoked correctly.</li>
   * </ol>
   *
   * @param innerExtensions the extensions registered on the inner proxy
   * @param realTarget      the deepest non-proxy target; used as terminal override
   *                        when a type-compatible chain link exists
   * @return a (possibly new) extension instance wired into the inner chain
   */
  default DispatchExtension linkInner(DispatchExtension[] innerExtensions,
                                      Object realTarget) {
    // Backward-compatible default: delegate to the legacy overload that
    // does not receive the real target. New extensions should override
    // this method directly.
    return linkInner(innerExtensions);
  }

  /**
   * Legacy linking without real-target override.
   *
   * <p>Kept for backward compatibility with extensions written before the
   * real-target parameter was introduced. New extensions should override
   * {@link #linkInner(DispatchExtension[], Object)} instead.</p>
   *
   * <p>The default implementation returns {@code this} unchanged, meaning
   * no chain-walk optimization is performed — the extension will dispatch
   * through the inner proxy normally.</p>
   *
   * @param innerExtensions the extensions registered on the inner proxy
   * @return this extension, unchanged
   */
  default DispatchExtension linkInner(DispatchExtension[] innerExtensions) {
    return this;
  }

  /**
   * Returns {@code true} if this extension handles <em>every</em> method
   * regardless of signature or return type.
   *
   * <p>Catch-all extensions serve as the fallback — they guarantee that no
   * method goes unhandled. The framework enforces the following rules:</p>
   * <ul>
   *   <li>Exactly one catch-all must exist in the extension array.</li>
   *   <li>The catch-all must be the <strong>last</strong> extension, so that
   *       more specific extensions get first match.</li>
   *   <li>{@link ProxyWrapper#createProxy} validates these rules at construction
   *       time, failing immediately if violated.</li>
   * </ul>
   *
   * @return {@code true} if this extension is a catch-all; {@code false} by default
   */
  default boolean isCatchAll() {
    return false;
  }

  /**
   * Unwraps reflection wrappers and classifies the exception for proper propagation.
   *
   * <p>{@link UndeclaredThrowableException} and {@link InvocationTargetException}
   * are common reflection artifacts that hide the real cause. This method peels
   * them off iteratively before deciding how to propagate the underlying exception.</p>
   *
   * <h3>Propagation rules (after unwrapping):</h3>
   * <ol>
   *   <li><strong>RuntimeException:</strong> rethrown directly — already unchecked.</li>
   *   <li><strong>Error:</strong> rethrown directly — should not be caught or wrapped.</li>
   *   <li><strong>Declared checked exception:</strong> if the exception type is declared
   *       in the method's {@code throws} clause, it is rethrown via
   *       {@link Throws#rethrow} (sneaky throw) to preserve the original type.</li>
   *   <li><strong>Undeclared checked exception:</strong> wrapped in
   *       {@link IllegalStateException} with a diagnostic message, because the caller
   *       cannot handle an exception type that the method doesn't declare.</li>
   * </ol>
   *
   * @param method the method that was being invoked (used to check declared exceptions)
   * @param e      the exception caught during dispatch
   * @return never returns — always throws. The return type is {@code RuntimeException}
   *         to allow callers to write {@code throw handleException(method, e)} for
   *         control-flow analysis.
   */
  default RuntimeException handleException(Method method, Throwable e) {
    // Iteratively unwrap reflection wrappers to expose the real cause.
    // Both UndeclaredThrowableException and InvocationTargetException wrap
    // the real exception in their cause field.
    Throwable cause = e;
    while (cause instanceof UndeclaredThrowableException
        || cause instanceof InvocationTargetException) {
      Throwable next = cause.getCause();
      if (next == null) break; // Guard against null cause (shouldn't happen but be safe)
      cause = next;
    }

    // Runtime exceptions propagate directly — they are already unchecked
    if (cause instanceof RuntimeException re) throw re;
    // Errors (OutOfMemoryError, StackOverflowError, etc.) propagate directly —
    // they should never be wrapped
    if (cause instanceof Error err) throw err;

    // Check if the exception is declared in the method's throws clause.
    // If so, rethrow it via sneaky-throw to preserve the original type
    // without requiring a checked-exception declaration on this method.
    for (Class<?> declared : method.getExceptionTypes()) {
      if (declared.isInstance(cause)) {
        throw Throws.rethrow(cause);
      }
    }

    // The exception is a checked exception that is NOT declared in the method's
    // throws clause. Wrap it with a diagnostic message so developers can identify
    // where the undeclared exception originated.
    throw new IllegalStateException(
        "Undeclared checked exception from "
            + method.getDeclaringClass().getSimpleName()
            + "." + method.getName(), cause);
  }

}
