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
 * <p>Each {@link DispatchExtension} holds its own instance, scoping the
 * cache to the extension's lifetime. When extensions are linked during
 * proxy chaining, the new extension instance gets a fresh cache — this
 * keeps cache sizes proportional to the methods actually dispatched
 * through each extension and avoids a global singleton.</p>
 *
 * <h3>Hot-path optimisation</h3>
 * <p>Instead of the generic {@link MethodHandle#invokeWithArguments} path
 * (which copies arrays internally and performs runtime type checks on every
 * call), this cache dispatches through arity-specialised invocations for
 * 0–3 parameters. Methods with 4+ parameters use a pre-built
 * {@linkplain MethodHandle#asSpreader spreader} handle that consumes the
 * argument array directly — eliminating the per-call array allocation that
 * was previously needed to prepend the target.</p>
 *
 * <h3>Thread safety</h3>
 * <p>The internal map is a {@link ConcurrentHashMap}. Concurrent
 * {@code computeIfAbsent} calls for the same {@code Method} key may
 * redundantly unreflect, but the result is identical and idempotent.</p>
 *
 * @since 0.6.0
 */
public final class MethodHandleCache {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  /**
   * Cached resolved handle per method.
   */
  private final ConcurrentHashMap<Method, MethodHandle> fastCache =
      new ConcurrentHashMap<>();

  /**
   * Cached spreader handle per method (only for arity >= 4).
   * The spreader accepts (Object target, Object[] args) and spreads args
   * into positional parameters, avoiding the manual array copy.
   */
  private final ConcurrentHashMap<Method, MethodHandle> spreaderCache =
      new ConcurrentHashMap<>();


  private static MethodHandle unreflect(Method method) {
    try {
      return LOOKUP.unreflect(method);
    } catch (IllegalAccessException e) {
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
   * <p>On first access the method is made accessible and unreflected.
   * The resulting handle is stored and reused for all future calls
   * within this cache instance.</p>
   *
   * @param method the reflected method (must not be {@code null})
   * @return a reusable {@code MethodHandle}
   */
  MethodHandle resolve(Method method) {
    MethodHandle mh = fastCache.get(method);
    if (mh == null) {
      mh = fastCache.computeIfAbsent(method, MethodHandleCache::unreflect);
    }
    return mh;
  }

  /**
   * Invokes the cached handle for {@code method} on the given target
   * with the supplied arguments.
   *
   * <p>Uses arity-specialised dispatch for 0–3 parameters to avoid
   * {@link MethodHandle#invokeWithArguments} overhead. For methods with
   * 4+ parameters a pre-built spreader handle is used that consumes the
   * argument array directly, eliminating the per-call array allocation.</p>
   *
   * @param method the service method
   * @param target the object to invoke on
   * @param args   the method arguments ({@code null} for zero-arg methods)
   * @return the method's return value
   * @throws Throwable if the underlying method throws
   */
  public Object invoke(Object target, Method method, Object[] args) throws Throwable {
    int arity = (args == null) ? 0 : args.length;

    if (arity < 6) {
      MethodHandle mh = resolve(method);

      return switch (arity) {
        case 0 -> mh.invoke(target);
        case 1 -> mh.invoke(target, args[0]);
        case 2 -> mh.invoke(target, args[0], args[1]);
        case 3 -> mh.invoke(target, args[0], args[1], args[2]);
        case 4 -> mh.invoke(target, args[0], args[1], args[2], args[3]);
        case 5 -> mh.invoke(target, args[0], args[1], args[2], args[3], args[4]);
        default -> resolveSpreader(method, arity).invoke(target, args);
      };
    } else {
      MethodHandle mh = resolveSpreader(method, arity);
      return mh.invoke(target, args);
    }
  }

  /**
   * Returns a cached spreader handle for high-arity methods (4+ params).
   *
   * <p>The spreader is built from the original handle by first casting all
   * parameter types to {@code Object} (via {@link MethodHandle#asType}) and
   * then converting it into a {@code (Object, Object[]) -> Object} form
   * via {@link MethodHandle#asSpreader}. This lets us pass the target and
   * the argument array directly — no intermediate array copy required.</p>
   */
  private MethodHandle resolveSpreader(Method method, int arity) {
    MethodHandle mh = spreaderCache.get(method);
    if (mh == null) {
      mh = spreaderCache.computeIfAbsent(method, k -> {
        // Build generic type: (Object, Object, Object, ...) -> Object
        MethodType generic = MethodType.genericMethodType(arity + 1);
        MethodHandle adapted = unreflect(method).asType(generic);
        // Convert trailing params to spreader: (Object target, Object[] args) -> Object
        return adapted.asSpreader(Object[].class, arity);
      });
    }
    return mh;
  }
}
