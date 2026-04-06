package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.Wrapper;

/**
 * Factory for creating sync dynamic proxies that wrap service method invocations
 * through a {@link LayerAction}.
 *
 * <p>Proxies implement the {@link Wrapper} interface and are chain-aware.
 * For async method routing, use {@code InqAsyncProxyFactory}.</p>
 *
 * @since 0.4.0
 */
public interface InqProxyFactory {

  @SuppressWarnings("unchecked")
  static InqProxyFactory of(String name, LayerAction<?, ?> action) {
    LayerAction<Void, Object> sync = (LayerAction<Void, Object>) action;
    SyncDispatchExtension extension = new SyncDispatchExtension(sync);
    return new InqProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        ProxyWrapper.validateInterface(serviceInterface);
        return ProxyWrapper.createProxy(serviceInterface, target, name, extension);
      }
    };
  }

  static InqProxyFactory of(LayerAction<?, ?> action) {
    return of("proxy", action);
  }

  <T> T protect(Class<T> serviceInterface, T target);
}
