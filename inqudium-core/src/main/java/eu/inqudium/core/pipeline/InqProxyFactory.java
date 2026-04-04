package eu.inqudium.core.pipeline;

/**
 * Factory for creating sync dynamic proxies that wrap service method invocations
 * through a {@link LayerAction}.
 *
 * <p>Proxies implement the {@link Wrapper} interface and are chain-aware.
 * For async method routing, use {@code InqAsyncProxyFactory}.</p>
 *
 * @since 0.4.0
 */
@FunctionalInterface
public interface InqProxyFactory {

  <T> T protect(Class<T> serviceInterface, T target);

  @SuppressWarnings("unchecked")
  static InqProxyFactory of(String name, LayerAction<?, ?> action) {
    LayerAction<Void, Object> sync = (LayerAction<Void, Object>) action;
    return new InqProxyFactory() {
      @Override public <T> T protect(Class<T> serviceInterface, T target) {
        PipelineInvocationHandler.validateInterface(serviceInterface);
        return PipelineInvocationHandler.createProxy(serviceInterface, target, name, sync);
      }
    };
  }

  static InqProxyFactory of(LayerAction<?, ?> action) {
    return of("proxy", action);
  }
}
