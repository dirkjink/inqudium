package eu.inqudium.core.pipeline;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
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
 * <h3>Why this matters</h3>
 * <p>Every call to {@link Method#invoke} performs access checks, argument
 * validation, and boxing — work that the JVM cannot fully eliminate even
 * when the same method is called millions of times. A {@code MethodHandle}
 * obtained via {@link MethodHandles.Lookup#unreflect} encodes the access
 * decision once; subsequent invocations are a direct function-pointer call
 * that the JIT can inline.</p>
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

  private final ConcurrentHashMap<Method, MethodHandle> cache =
      new ConcurrentHashMap<>();

  private static MethodHandle unreflect(Method method) {
    try {
      method.setAccessible(true);
      return LOOKUP.unreflect(method);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(
          "Failed to unreflect method: " + method, e);
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
  public MethodHandle resolve(Method method) {
    return cache.computeIfAbsent(method, MethodHandleCache::unreflect);
  }

  /**
   * Invokes the cached handle for {@code method} on the given target
   * with the supplied arguments.
   *
   * <p>Handles the {@code null}-args convention that
   * {@link java.lang.reflect.InvocationHandler} uses for zero-argument
   * methods.</p>
   *
   * @param method the service method
   * @param target the object to invoke on
   * @param args   the method arguments ({@code null} for zero-arg methods)
   * @return the method's return value
   * @throws Throwable if the underlying method throws
   */
  public Object invoke(Method method, Object target, Object[] args) throws Throwable {
    MethodHandle mh = resolve(method);
    if (args == null || args.length == 0) {
      return mh.invoke(target);
    }
    Object[] full = new Object[args.length + 1];
    full[0] = target;
    System.arraycopy(args, 0, full, 1, args.length);
    return mh.invokeWithArguments(full);
  }
}
