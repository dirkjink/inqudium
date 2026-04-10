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
 * <h3>Hot-path optimization</h3>
 * <p>Instead of the generic {@link MethodHandle#invokeWithArguments} path
 * (which copies arrays internally and performs runtime type checks on every
 * call), this cache dispatches through arity-specialized invocations:</p>
 * <ul>
 *   <li><strong>0–5 parameters:</strong> Direct {@code mh.invoke(target, arg0, arg1, ...)}
 *       calls — no array allocation, no copying.</li>
 *   <li><strong>6+ parameters:</strong> A pre-built {@linkplain MethodHandle#asSpreader spreader}
 *       handle that consumes the argument array directly, eliminating the per-call
 *       array allocation that would be needed to prepend the target to the args.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>The internal maps are {@link ConcurrentHashMap}s. Concurrent
 * {@code computeIfAbsent} calls for the same {@code Method} key may
 * redundantly unreflect, but the result is identical and idempotent —
 * no locking is needed beyond what {@code ConcurrentHashMap} provides.</p>
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
     * Primary cache: maps each {@code Method} to its resolved {@code MethodHandle}.
     * Used for all arity levels. Populated lazily on first access per method.
     */
    private final ConcurrentHashMap<Method, MethodHandle> fastCache =
            new ConcurrentHashMap<>();

    /**
     * Secondary cache for spreader handles, used only for methods with 6+
     * parameters. A spreader handle accepts {@code (Object target, Object[] args)}
     * and spreads the args array into positional parameters, avoiding the manual
     * array copy that would otherwise be needed.
     */
    private final ConcurrentHashMap<Method, MethodHandle> spreaderCache =
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
        MethodHandle mh = fastCache.get(method);
        if (mh == null) {
            // Slow path: compute and cache on first access.
            // computeIfAbsent guarantees at-most-one computation per key,
            // though concurrent calls may redundantly enter the lambda.
            mh = fastCache.computeIfAbsent(method, MethodHandleCache::unreflect);
        }
        return mh;
    }

    /**
     * Invokes the cached handle for {@code method} on the given target
     * with the supplied arguments.
     *
     * <p>Uses arity-specialized dispatch to minimize overhead:</p>
     * <ul>
     *   <li><strong>0–5 args:</strong> Direct {@code mh.invoke(target, arg0, ...)} —
     *       each argument is passed as a separate parameter. No array allocation,
     *       no spreading, maximum JIT optimization potential.</li>
     *   <li><strong>6+ args:</strong> Pre-built spreader handle that accepts
     *       {@code (Object target, Object[] args)} and internally spreads the
     *       array into positional parameters. The spreader is cached, so the
     *       conversion cost is paid only once per method.</li>
     * </ul>
     *
     * @param target the object to invoke the method on
     * @param method the service method (used as cache key)
     * @param args   the method arguments ({@code null} for zero-arg methods)
     * @return the method's return value
     * @throws Throwable if the underlying method throws
     */
    public Object invoke(Object target, Method method, Object[] args) throws Throwable {
        int arity = (args == null) ? 0 : args.length;

        // Arity-specialized dispatch for common cases (0–5 parameters).
        // Each case passes arguments directly to avoid array operations.
        if (arity < 6) {
            MethodHandle mh = resolve(method);

            return switch (arity) {
                case 0 -> mh.invoke(target);
                case 1 -> mh.invoke(target, args[0]);
                case 2 -> mh.invoke(target, args[0], args[1]);
                case 3 -> mh.invoke(target, args[0], args[1], args[2]);
                case 4 -> mh.invoke(target, args[0], args[1], args[2], args[3]);
                case 5 -> mh.invoke(target, args[0], args[1], args[2], args[3], args[4]);
                // Unreachable due to the arity < 6 guard, but required by the compiler
                default -> resolveSpreader(method, arity).invoke(target, args);
            };
        } else {
            // High-arity path: use a pre-built spreader handle that accepts
            // (Object target, Object[] args) and spreads the array internally.
            MethodHandle mh = resolveSpreader(method, arity);
            return mh.invoke(target, args);
        }
    }

    /**
     * Returns a cached spreader handle for high-arity methods (6+ parameters).
     *
     * <p>The spreader is built in two steps from the original handle:</p>
     * <ol>
     *   <li>{@link MethodHandle#asType} casts all parameter types to {@code Object}
     *       and the return type to {@code Object}, creating a fully generic handle.</li>
     *   <li>{@link MethodHandle#asSpreader} converts the trailing parameters into a
     *       single {@code Object[]} parameter, producing a handle with signature
     *       {@code (Object target, Object[] args) -> Object}.</li>
     * </ol>
     *
     * <p>This lets us pass the target and the argument array directly — no
     * intermediate array copy required to prepend the target.</p>
     *
     * @param method the service method (used as cache key)
     * @param arity  the number of method parameters (excluding the target)
     * @return a cached spreader handle
     */
    private MethodHandle resolveSpreader(Method method, int arity) {
        // Fast path: check without locking
        MethodHandle mh = spreaderCache.get(method);
        if (mh == null) {
            // Slow path: build the spreader and cache it
            mh = spreaderCache.computeIfAbsent(method, k -> {
                // Build a fully generic method type: (Object, Object, Object, ...) -> Object
                // The +1 accounts for the target parameter (first positional arg)
                MethodType generic = MethodType.genericMethodType(arity + 1);
                MethodHandle adapted = unreflect(method).asType(generic);
                // Convert the trailing 'arity' parameters into a single Object[] spreader,
                // resulting in: (Object target, Object[] args) -> Object
                return adapted.asSpreader(Object[].class, arity);
            });
        }
        return mh;
    }
}
