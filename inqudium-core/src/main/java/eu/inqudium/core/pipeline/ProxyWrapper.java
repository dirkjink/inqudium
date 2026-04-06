package eu.inqudium.core.pipeline;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Invocation handler that dispatches service methods through composable
 * {@link DispatchExtension} layers.
 *
 * <p>{@code ProxyWrapper} itself contains no sync/async logic — all dispatch
 * modes are pluggable extensions. Extensions are checked in registration order;
 * the first whose {@link DispatchExtension#canHandle} returns {@code true}
 * receives the call.</p>
 *
 * <h3>Chain correctness</h3>
 * <p>The dispatch target passed to extensions is the <em>proxy target</em> —
 * the JDK proxy object (or real object) that this wrapper was built around.
 * Extensions that successfully link with a type-compatible inner extension
 * override this target with the deep {@code realTarget} for a zero-reentry
 * chain walk. Extensions that do <em>not</em> find a match invoke the proxy
 * target, which re-enters the inner proxy and correctly dispatches through
 * its extensions.</p>
 *
 * <h3>Performance</h3>
 * <p>Extensions are stored as a plain array — no {@code List} wrapper, no iterator
 * allocation. The dispatch loop on the hot path is a simple indexed {@code for} with
 * zero object allocation beyond the per-invocation terminal lambda.</p>
 *
 * @since 0.5.0
 */
public class ProxyWrapper extends AbstractProxyWrapper {

  private static final DispatchExtension[] EMPTY = new DispatchExtension[0];

  private final DispatchExtension[] extensions;

  /**
   * The original target object (JDK proxy or real object) passed to
   * {@link #createProxy}. Used as the default terminal invocation target.
   *
   * <p>For the innermost proxy this equals the real target. For outer proxies
   * it is the inner JDK proxy, ensuring that unlinked extensions still
   * dispatch through the inner proxy's extension pipeline.</p>
   */
  private final Object proxyTarget;

  // ======================== Constructors ========================

  protected ProxyWrapper(String name, Object delegate, Object proxyTarget,
                         DispatchExtension[] extensions) {
    super(name, delegate);
    this.proxyTarget = proxyTarget;

    if (delegate instanceof ProxyWrapper inner) {
      DispatchExtension[] innerExts = inner.extensions;
      Object rt = realTarget(); // deep target for linked optimisation
      DispatchExtension[] linked = new DispatchExtension[extensions.length];
      for (int i = 0; i < extensions.length; i++) {
        linked[i] = extensions[i].linkInner(innerExts, rt);
      }
      this.extensions = linked;
    } else {
      this.extensions = extensions.clone();
    }
  }

  // ======================== Accessors ========================

  /**
   * Creates a proxy with the given extensions. Extensions are checked in order —
   * register the catch-all (typically {@link SyncDispatchExtension}) last.
   */
  @SuppressWarnings("unchecked")
  public static <T> T createProxy(Class<T> serviceInterface, T target, String name,
                                  DispatchExtension... extensions) {
    AbstractProxyWrapper inner = resolveInner(target);
    Object delegate = (inner != null) ? inner : target;
    ProxyWrapper handler = new ProxyWrapper(name, delegate, target, extensions);
    return (T) Proxy.newProxyInstance(
        serviceInterface.getClassLoader(),
        new Class<?>[]{serviceInterface, Wrapper.class},
        handler);
  }

  // ======================== Factory methods ========================

  /**
   * Returns the extensions on this handler (for inner-resolution during chaining).
   */
  DispatchExtension[] extensions() {
    return extensions;
  }

  // ======================== Dispatch ========================

  /**
   * Dispatches the service method through the first matching extension.
   *
   * <p>The invocation target passed to the extension is {@link #proxyTarget} —
   * the JDK proxy (or real object) this wrapper was constructed around.
   * Extensions that have been linked via {@link DispatchExtension#linkInner}
   * override this with the deep real target for their optimised chain walk;
   * all other extensions invoke the proxy target, preserving correct
   * composition across heterogeneous extension types.</p>
   */
  @Override
  protected Object dispatchServiceMethod(Method method, Object[] args) throws Throwable {
    long chainId = chainId();
    long callId = generateCallId();

    for (int i = 0; i < extensions.length; i++) {
      DispatchExtension ext = extensions[i];
      if (ext.canHandle(method)) {
        return ext.dispatch(chainId, callId, method, args, proxyTarget);
      }
    }

    throw new UnsupportedOperationException(
        "No dispatch extension handles method: " + method
            + " — ensure a catch-all extension (e.g. SyncDispatchExtension) is registered last.");
  }
}
