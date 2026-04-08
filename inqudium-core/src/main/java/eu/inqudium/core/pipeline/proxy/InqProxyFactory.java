package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.Wrapper;

/**
 * Factory for creating synchronous dynamic proxies that wrap service method
 * invocations through a {@link LayerAction}.
 *
 * <p>This is the primary entry point for application code that wants to protect
 * a service interface with cross-cutting concerns (timing, resilience, logging, etc.)
 * without modifying the service implementation. The resulting proxy:</p>
 * <ul>
 *   <li>Implements the service interface — callers interact with it exactly as
 *       they would with the real implementation.</li>
 *   <li>Implements the {@link Wrapper} interface — enabling chain introspection
 *       via {@code chainId()}, {@code inner()}, {@code toStringHierarchy()}, etc.</li>
 *   <li>Is chain-aware — stacking multiple proxies automatically triggers
 *       chain-walk optimization via {@link SyncDispatchExtension#linkInner}.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * LayerAction<Void, Object> timing = (chainId, callId, arg, next) -> {
 *     long start = System.nanoTime();
 *     Object result = next.execute(chainId, callId, arg);
 *     metrics.record(System.nanoTime() - start);
 *     return result;
 * };
 *
 * InqProxyFactory factory = InqProxyFactory.of("timing", timing);
 * MyService proxy = factory.protect(MyService.class, realService);
 * proxy.doWork(); // routed through the timing action
 * }</pre>
 *
 * <h3>Stacking proxies</h3>
 * <pre>{@code
 * MyService inner = InqProxyFactory.of("logging", loggingAction)
 *     .protect(MyService.class, realService);
 * MyService outer = InqProxyFactory.of("auth", authAction)
 *     .protect(MyService.class, inner);
 * // Execution order: auth → logging → realService
 * }</pre>
 *
 * <p>For async method routing, use the corresponding {@code InqAsyncProxyFactory}
 * (not part of this module).</p>
 *
 * @since 0.4.0
 */
public interface InqProxyFactory {

  /**
   * Creates a factory that wraps service methods through the given {@link LayerAction}
   * with a custom layer name.
   *
   * <p>The action's type parameters are cast to {@code <Void, Object>} because
   * proxy dispatch always passes {@code null} as the argument and returns raw
   * {@code Object}. The unchecked cast is unavoidable due to type erasure but
   * is guarded by the dispatch contract — the action never uses the argument
   * parameter for synchronous dispatch.</p>
   *
   * @param name   a human-readable name for the proxy layer (appears in
   *               {@code layerDescription()} and {@code toStringHierarchy()})
   * @param action the around-advice to apply to every proxied method call
   * @return a new factory instance ready to create proxies
   * @throws IllegalArgumentException if {@code action} is null
   */
  @SuppressWarnings("unchecked")
  static InqProxyFactory of(String name, LayerAction<?, ?> action) {
    // Null check — fail fast with a clear message
    if (action == null) {
      throw new IllegalArgumentException("LayerAction must not be null.");
    }

    // Cast to the proxy dispatch type parameters.
    // Safe at runtime: SyncDispatchExtension always passes null as the first
    // generic parameter and forwards the raw Object result. The unchecked cast
    // is unavoidable due to type erasure but guarded by the dispatch contract.
    LayerAction<Void, Object> sync = (LayerAction<Void, Object>) action;

    // Create the catch-all sync extension that will handle all method calls
    SyncDispatchExtension extension = new SyncDispatchExtension(sync);

    // Return an anonymous factory implementation that creates proxies
    // using ProxyWrapper.createProxy with the single sync extension
    return new InqProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        // Validate that the target type is an interface (required by JDK proxies)
        ProxyWrapper.validateInterface(serviceInterface);
        // Create the proxy with the sync extension as the sole (catch-all) extension
        return ProxyWrapper.createProxy(serviceInterface, target, name, extension);
      }
    };
  }

  /**
   * Creates a factory with the default layer name "proxy".
   *
   * <p>Convenience overload for cases where the layer name doesn't matter
   * (e.g. single-layer proxies or quick prototyping).</p>
   *
   * @param action the around-advice to apply to every proxied method call
   * @return a new factory instance with the default name
   */
  static InqProxyFactory of(LayerAction<?, ?> action) {
    return of("proxy", action);
  }

  /**
   * Creates a JDK dynamic proxy that routes all method calls on the
   * service interface through this factory's configured {@link LayerAction}.
   *
   * <p>The returned proxy implements both the service interface and the
   * {@link Wrapper} interface. It can be used as a drop-in replacement for
   * the target and also supports chain introspection via casting to
   * {@code Wrapper}.</p>
   *
   * @param serviceInterface the interface to proxy (must be an interface, not a class)
   * @param target           the real implementation to wrap
   * @param <T>              the service interface type
   * @return a proxy that routes all method calls through the layer action
   * @throws IllegalArgumentException if the type is not an interface
   */
  <T> T protect(Class<T> serviceInterface, T target);
}
