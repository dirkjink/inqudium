package eu.inqudium.core.pipeline.proxy;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/**
 * Pre-built, per-method invocation adapter.
 *
 * <p>A {@code MethodInvoker} encapsulates the arity-specialized dispatch path
 * for a specific {@link Method}. It is built once — at the time the method is
 * first resolved by {@link MethodHandleCache#resolveInvoker} — and reused for
 * every subsequent invocation.</p>
 *
 * <h3>Motivation: eliminating the per-call arity switch</h3>
 * <p>Dispatching through {@link MethodHandle} requires arity-specific invocation
 * signatures (e.g. {@code mh.invoke(target, a0)} vs. {@code mh.invoke(target, a0, a1, a2)}).
 * A naive cache selects the right signature via a {@code switch} on the argument
 * count at every call. This has two hidden costs:</p>
 * <ol>
 *   <li><strong>The switch itself</strong> — a few cycles per call.</li>
 *   <li><strong>Multiple {@code MethodHandle.invoke} call-sites in one method</strong>
 *       — the JIT must manage inline caches for each, which for methods of
 *       varying arity can transition into the bimorphic or megamorphic regime
 *       and trigger deoptimization cycles.</li>
 * </ol>
 *
 * <p>By capturing the correct invocation signature inside a lambda at resolution
 * time, every {@code MethodInvoker} instance has exactly <strong>one</strong>
 * {@code mh.invoke(...)} site in its body. The call-site at
 * {@code invoker.invoke(target, args)} sees a small, bounded set of implementation
 * classes (one per arity-specialization), and — critically — for any specific
 * hot method, the captured {@link MethodHandle} is constant, enabling the JIT
 * to fully inline through the handle.</p>
 *
 * <h3>Observed benefit</h3>
 * <p>The five-argument proxy benchmark previously exhibited a ±27.6 ns variance
 * attributable to JIT oscillation between specialization strategies at the
 * arity switch. Pre-built invokers remove the switch from the hot path and
 * stabilize the call-site.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Implementations are stateless beyond their captured {@link MethodHandle}
 * and are safe for concurrent use.</p>
 *
 * @see MethodHandleCache#resolveInvoker(Method)
 * @since 0.8.0
 */
@FunctionalInterface
public interface MethodInvoker {

    /**
     * Invokes the underlying method on the given target with the given arguments.
     *
     * <p>The invoker is arity-specialized for the method it was built for —
     * the caller is responsible for passing an argument array of the correct
     * size. For zero-argument methods, the {@code args} parameter is not
     * accessed and may safely be {@code null} or empty.</p>
     *
     * @param target the object on which to invoke the method
     * @param args   the arguments for the invocation; must match the arity of
     *               the underlying method
     * @return the method's return value (or {@code null} for {@code void} methods)
     * @throws Throwable any exception thrown by the underlying method, unwrapped
     *                   from reflection's {@code InvocationTargetException}
     */
    Object invoke(Object target, Object[] args) throws Throwable;
}
