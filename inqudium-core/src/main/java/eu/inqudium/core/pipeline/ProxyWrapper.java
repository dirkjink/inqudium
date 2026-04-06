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

  // ======================== Constructors ========================

  protected ProxyWrapper(String name, Object delegate, DispatchExtension[] extensions) {
    super(name, delegate);

    if (delegate instanceof ProxyWrapper inner) {
      DispatchExtension[] innerExts = inner.extensions;
      DispatchExtension[] linked = new DispatchExtension[extensions.length];
      for (int i = 0; i < extensions.length; i++) {
        linked[i] = extensions[i].linkInner(innerExts);
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
    ProxyWrapper handler = new ProxyWrapper(name, delegate, extensions);
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

  @Override
  protected Object dispatchServiceMethod(Method method, Object[] args) throws Throwable {
    long chainId = chainId();
    long callId = generateCallId();

    for (int i = 0; i < extensions.length; i++) {
      DispatchExtension ext = extensions[i];
      if (ext.canHandle(method)) {
        return ext.dispatch(chainId, callId, method, args, realTarget());
      }
    }

    throw new UnsupportedOperationException(
        "No dispatch extension handles method: " + method
            + " — ensure a catch-all extension (e.g. SyncDispatchExtension) is registered last.");
  }
}
