package eu.inqudium.core.pipeline.proxy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, instance-level cache that converts {@link Method} references
 * to {@link MethodHandle}s on first use and reuses them for all subsequent
 * invocations of the same method.
 *
 * <p>{@code MethodHandle}s are significantly faster than {@code Method.invoke()}
 * for repeated calls because they bypass the security checks and method lookup
 * that reflection performs on every invocation. By caching the handle, the
 * expensive unreflection step happens only once per method.</p>
 *
 * <h3>Instance-level scoping</h3>
 * <p>Each {@link DispatchExtension} holds its own {@code MethodHandleCache} instance,
 * scoping the cache to the extension's lifetime. When extensions are linked during
 * proxy chaining, the new extension instance may inherit the outer extension's cache
 * (to reuse already-resolved handles) or get a fresh one — this keeps cache sizes
 * proportional to the methods actually dispatched through each extension and avoids
 * a global singleton with unbounded growth.</p>
 *
 * <h3>Hot-path optimization: pre-built per-method invokers</h3>
 * <p>The original design dispatched through an arity-switch <em>on every call</em>
 * — choosing one of seven {@code MethodHandle.invoke} signatures at runtime.
 * That approach had two hidden costs on the hot path: the switch itself, and
 * the multiple {@code mh.invoke} call-sites inside a single method, which
 * caused the JIT to oscillate between specializations for methods of varying
 * arity (visible as the ±27.6 ns variance previously observed on the 5-arg
 * benchmark).</p>
 *
 * <p>This implementation moves the arity decision to <em>resolution time</em>:
 * {@link #resolveInvoker(Method)} returns a cached, per-method
 * {@link MethodInvoker} whose body contains exactly one pre-chosen
 * {@code mh.invoke} signature. The hot path becomes a single map lookup
 * followed by a direct lambda call with no runtime branching on arity.</p>
 *
 * <ul>
 *   <li><strong>0–5 parameters:</strong> direct {@code mh.invoke(target, arg0, ...)}
 *       captured in the invoker — no array operations, no switch per call.</li>
 *   <li><strong>6+ parameters:</strong> a pre-built
 *       {@linkplain MethodHandle#asSpreader spreader} handle is captured in
 *       the invoker and accepts the argument array directly.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>The internal maps are {@link ConcurrentHashMap}s. Concurrent
 * {@code computeIfAbsent} calls for the same {@code Method} key may redundantly
 * compute the same value, but the result is identical and idempotent — no
 * locking is needed beyond what {@code ConcurrentHashMap} provides.</p>
 *
 * @since 0.6.0
 */
public final class MethodHandleCache {

    /**
     * Shared lookup object for unreflecting methods. Created once from the
     * context of this class — its access level determines which methods can
     * be unreflected without making them accessible first.
     */
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * Reusable empty-arguments constant — avoids per-call allocation when
     * {@link #invoke(Object, Method, Object[])} is called with {@code null}
     * for a zero-arg method.
     */
    private static final Object[] EMPTY_ARGS = new Object[0];

    /**
     * Primary cache: maps each {@code Method} to its resolved {@code MethodHandle}.
     * Populated lazily on first access per method. Exposed through {@link #resolve}
     * for callers that need the raw handle.
     */
    private final ConcurrentHashMap<Method, MethodHandle> handleCache =
            new ConcurrentHashMap<>();

    /**
     * Invoker cache: maps each {@code Method} to a pre-built, arity-specialized
     * {@link MethodInvoker}. This is the primary hot-path cache — consulted by
     * both {@link #resolveInvoker} and {@link #invoke} to eliminate the per-call
     * arity switch and the redundant handle lookup that the previous
     * implementation required.
     */
    private final ConcurrentHashMap<Method, MethodInvoker> invokerCache =
            new ConcurrentHashMap<>();

    /**
     * Converts a {@code Method} to a {@code MethodHandle} via unreflection.
     *
     * <p>If the initial unreflect fails due to access restrictions (e.g. the method
     * is package-private or in a non-exported module), falls back to making the
     * method accessible and retrying. This ensures that proxy dispatch works even
     * for non-public interface methods.</p>
     *
     * @param method the reflected method to unreflect
     * @return a usable {@code MethodHandle}
     * @throws IllegalStateException if unreflection fails even after setAccessible
     */
    private static MethodHandle unreflect(Method method) {
        try {
            return LOOKUP.unreflect(method);
        } catch (IllegalAccessException e) {
            // Fallback: force accessibility and retry.
            // This handles non-public methods and methods in restricted modules.
            try {
                method.setAccessible(true);
                return LOOKUP.unreflect(method);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to unreflect method: " + method, e);
            }
        }
    }

    /**
     * Returns a cached {@link MethodHandle} for the given method.
     *
     * <p>On first access the method is unreflected (and made accessible if needed).
     * The resulting handle is stored in the cache and reused for all future calls
     * within this cache instance.</p>
     *
     * <p>Uses a two-step lookup: first a plain {@code get()} (fast path, no locking),
     * then {@code computeIfAbsent()} only on cache miss. This avoids the overhead
     * of {@code computeIfAbsent}'s internal locking on every call.</p>
     *
     * @param method the reflected method (must not be {@code null})
     * @return a reusable {@code MethodHandle} for the method
     */
    MethodHandle resolve(Method method) {
        // Fast path: check without locking — hits on every call after the first
        MethodHandle mh = handleCache.get(method);
        if (mh == null) {
            // Slow path: compute and cache on first access.
            // computeIfAbsent guarantees at-most-one effective computation per key.
            mh = handleCache.computeIfAbsent(method, MethodHandleCache::unreflect);
        }
        return mh;
    }

    /**
     * Returns a cached {@link MethodInvoker} for the given method.
     *
     * <p>This is the primary entry point for dispatch extensions on the hot path.
     * The returned invoker has its arity-specialization already chosen at build
     * time — callers simply hand it a target and an argument array, with no
     * runtime branching on arity.</p>
     *
     * <p>For methods with ≤ 5 parameters, the invoker holds a direct
     * {@code mh.invoke(target, arg0, …)} call. For methods with 6+ parameters,
     * it holds a pre-built spreader handle that accepts the argument array
     * directly. Either way, the hot-path call-site is stable and monomorphic
     * for any given method, enabling the JIT to fully inline through the
     * invoker into the underlying method handle.</p>
     *
     * <p>Uses the same two-step lookup pattern as {@link #resolve}: a plain
     * {@code get()} first, with {@code computeIfAbsent()} only on miss.</p>
     *
     * @param method the reflected method (must not be {@code null})
     * @return a reusable, arity-specialized invoker for the method
     */
    MethodInvoker resolveInvoker(Method method) {
        MethodInvoker invoker = invokerCache.get(method);
        if (invoker == null) {
            invoker = invokerCache.computeIfAbsent(method, this::buildInvoker);
        }
        return invoker;
    }

    /**
     * Invokes the cached handle for {@code method} on the given target
     * with the supplied arguments.
     *
     * <p>Delegates to {@link #resolveInvoker} and calls the resulting
     * pre-built invoker. The arity switch is evaluated once at invoker
     * construction, not on every call.</p>
     *
     * <p>For zero-argument methods, callers may pass {@code null} or an empty
     * array for {@code args} — both are normalized to the shared
     * {@link #EMPTY_ARGS} constant.</p>
     *
     * @param target the object to invoke the method on
     * @param method the service method (used as cache key)
     * @param args   the method arguments ({@code null} is treated as empty)
     * @return the method's return value
     * @throws Throwable if the underlying method throws
     */
    public Object invoke(Object target, Method method, Object[] args) throws Throwable {
        // Normalize null args — only relevant for zero-arg methods, where the
        // invoker body ignores the array anyway. This keeps the contract
        // compatible with callers that pass null for no-arg methods.
        Object[] safeArgs = (args == null) ? EMPTY_ARGS : args;
        return resolveInvoker(method).invoke(target, safeArgs);
    }

    /**
     * Builds a per-method {@link MethodInvoker} with arity-specialized dispatch
     * baked in. Invoked lazily by {@link #resolveInvoker} on cache miss.
     *
     * <p>For methods with ≤ 5 parameters, the generated invoker invokes the
     * underlying {@link MethodHandle} with the correct number of direct
     * positional arguments — no array operations, no switch, maximum JIT
     * optimization potential.</p>
     *
     * <p>For methods with 6+ parameters, the invoker captures a pre-built
     * spreader handle that accepts {@code (Object target, Object[] args)}
     * and spreads the array into positional parameters internally. The
     * spreader is built once, here, at resolution time.</p>
     *
     * @param method the method to build an invoker for
     * @return a reusable invoker specialized for {@code method}'s arity
     */
    private MethodInvoker buildInvoker(Method method) {
        MethodHandle mh = resolve(method);
        int arity = method.getParameterCount();

        // Arity-specialized invoker bodies. Each case compiles to a distinct
        // lambda class — the call-site at invoker.invoke(target, args) sees
        // a bounded set of types (7 total), well within the JIT's polymorphic
        // inline cache capacity.
        switch (arity) {
            case 0:
                return (t, a) -> mh.invoke(t);
            case 1:
                return (t, a) -> mh.invoke(t, a[0]);
            case 2:
                return (t, a) -> mh.invoke(t, a[0], a[1]);
            case 3:
                return (t, a) -> mh.invoke(t, a[0], a[1], a[2]);
            case 4:
                return (t, a) -> mh.invoke(t, a[0], a[1], a[2], a[3]);
            case 5:
                return (t, a) -> mh.invoke(t, a[0], a[1], a[2], a[3], a[4]);
            default:
                // High-arity: build the spreader once and capture it. The
                // invoker body is a single mh.invoke(target, args) call
                // through the generic (Object, Object[]) -> Object signature.
                MethodType generic = MethodType.genericMethodType(arity + 1);
                MethodHandle spreader = mh.asType(generic).asSpreader(Object[].class, arity);
                return (t, a) -> spreader.invoke(t, a);
        }
    }
}
