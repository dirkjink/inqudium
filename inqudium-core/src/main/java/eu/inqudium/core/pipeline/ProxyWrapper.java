package eu.inqudium.core.pipeline;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Invocation handler that dispatches service methods through composable
 * {@link DispatchExtension} layers.
 *
 * <p>{@code ProxyWrapper} itself contains no sync/async logic — all dispatch
 * modes are pluggable extensions. The extensions are checked in registration
 * order; the first extension whose {@link DispatchExtension#canHandle} returns
 * {@code true} receives the call. A {@link SyncDispatchExtension} registered last
 * serves as the catch-all fallback.</p>
 *
 * <h3>Extension chaining</h3>
 * <p>When wrapping another {@code ProxyWrapper}, each extension is linked to its
 * counterpart on the inner handler via {@link DispatchExtension#linkInner}. Each
 * extension type maintains its own independent chain — parallel to every other
 * extension's chain.</p>
 *
 * <h3>Adding new dispatch modes</h3>
 * <p>Implementing a new mode (e.g. reactive) requires only a new
 * {@link DispatchExtension} — this class remains unchanged.</p>
 *
 * @since 0.5.0
 */
public class ProxyWrapper extends AbstractProxyWrapper {

  private final List<DispatchExtension> extensions;

  // ======================== Constructors ========================

  protected ProxyWrapper(String name, Object delegate, List<DispatchExtension> extensions) {
    super(name, delegate);

    if (delegate instanceof ProxyWrapper inner) {
      this.extensions = extensions.stream()
          .map(ext -> ext.linkInner(inner.extensions()))
          .toList();
    } else {
      this.extensions = List.copyOf(extensions);
    }
  }

  // ======================== Accessors ========================

  /** Returns the extensions on this handler (for inner-resolution during chaining). */
  List<DispatchExtension> extensions() {
    return extensions;
  }

  // ======================== Factory methods ========================

  /**
   * Creates a proxy with the given extensions. Extensions are checked in order —
   * register the catch-all (typically {@link SyncDispatchExtension}) last.
   */
  @SuppressWarnings("unchecked")
  public static <T> T createProxy(Class<T> serviceInterface, T target, String name,
                                  DispatchExtension... extensions) {
    AbstractProxyWrapper inner = resolveInner(target);
    Object delegate = (inner != null) ? inner : target;
    ProxyWrapper handler = new ProxyWrapper(name, delegate, List.of(extensions));
    return (T) Proxy.newProxyInstance(
        serviceInterface.getClassLoader(),
        new Class<?>[]{serviceInterface, Wrapper.class},
        handler);
  }

  /**
   * Convenience: creates a sync-only proxy with a {@link SyncDispatchExtension}.
   */
  public static <T> T createSyncProxy(Class<T> serviceInterface, T target, String name,
                                      LayerAction<Void, Object> syncAction) {
    return createProxy(serviceInterface, target, name,
        new SyncDispatchExtension(syncAction));
  }

  // ======================== Dispatch ========================

  @Override
  protected Object dispatchServiceMethod(Method method, Object[] args) throws Throwable {
    long chainId = chainId();
    long callId = generateCallId();

    for (DispatchExtension ext : extensions) {
      if (ext.canHandle(method)) {
        return ext.dispatch(chainId, callId, method, args, realTarget());
      }
    }

    throw new UnsupportedOperationException(
        "No dispatch extension handles method: " + method
            + " — ensure a catch-all extension (e.g. SyncDispatchExtension) is registered last.");
  }
}
