package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.InqProxyFactory;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.ProxyWrapper;
import eu.inqudium.core.pipeline.SyncDispatchExtension;

import java.util.concurrent.CompletionStage;

/**
 * Factory for creating dynamic proxies that route method invocations through
 * both a sync {@link LayerAction} and an async {@link AsyncLayerAction}.
 *
 * <p>Methods returning {@link CompletionStage} are dispatched through the
 * {@link AsyncLayerAction#executeAsync}, all other methods go through the
 * sync {@link LayerAction#execute}.</p>
 *
 * @since 0.4.0
 */
public interface InqAsyncProxyFactory extends InqProxyFactory {

  @SuppressWarnings("unchecked")
  static InqAsyncProxyFactory of(String name,
                                 LayerAction<?, ?> syncAction,
                                 AsyncLayerAction<?, ?> asyncAction) {
    AsyncDispatchExtension asyncExt =
        new AsyncDispatchExtension((AsyncLayerAction<Void, Object>) asyncAction);
    SyncDispatchExtension syncExt =
        new SyncDispatchExtension((LayerAction<Void, Object>) syncAction);
    return new InqAsyncProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        ProxyWrapper.validateInterface(serviceInterface);
        return ProxyWrapper.createProxy(serviceInterface, target, name, asyncExt, syncExt);
      }
    };
  }

  static InqAsyncProxyFactory of(LayerAction<?, ?> syncAction,
                                 AsyncLayerAction<?, ?> asyncAction) {
    return of("proxy", syncAction, asyncAction);
  }

  <T> T protect(Class<T> serviceInterface, T target);
}
