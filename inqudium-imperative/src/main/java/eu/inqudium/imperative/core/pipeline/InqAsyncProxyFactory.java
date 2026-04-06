package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.InqProxyFactory;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.ProxyWrapper;
import eu.inqudium.core.pipeline.SyncDispatchExtension;
import eu.inqudium.core.pipeline.Wrapper;

import java.util.concurrent.CompletionStage;

/**
 * Factory for creating dynamic proxies that route method invocations through
 * both a sync {@link LayerAction} and an async {@link AsyncLayerAction}.
 *
 * <p>Methods returning {@link CompletionStage} (or subtypes like
 * {@link java.util.concurrent.CompletableFuture}) are dispatched through the
 * {@link AsyncLayerAction#executeAsync}, all other methods go through the
 * sync {@link LayerAction#execute}.</p>
 *
 * <p>Internally composes an {@link AsyncDispatchExtension} and a
 * {@link SyncDispatchExtension} into a single {@link ProxyWrapper}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqAsyncProxyFactory factory = InqAsyncProxyFactory.of("bulkhead", bulkhead, bulkhead);
 * PaymentService proxy = factory.protect(PaymentService.class, realService);
 *
 * proxy.charge(order);          // sync  → LayerAction.execute
 * proxy.chargeAsync(order);     // async → AsyncLayerAction.executeAsync
 * }</pre>
 *
 * @since 0.4.0
 */
public interface InqAsyncProxyFactory extends InqProxyFactory {

  @SuppressWarnings("unchecked")
  static InqAsyncProxyFactory of(String name,
                                 LayerAction<?, ?> syncAction,
                                 AsyncLayerAction<?, ?> asyncAction) {
    LayerAction<Void, Object> sync = (LayerAction<Void, Object>) syncAction;
    AsyncLayerAction<Void, Object> async = (AsyncLayerAction<Void, Object>) asyncAction;
    return new InqAsyncProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        ProxyWrapper.validateInterface(serviceInterface);
        return ProxyWrapper.createProxy(serviceInterface, target, name,
            new AsyncDispatchExtension(async),
            new SyncDispatchExtension(sync));
      }
    };
  }

  static InqAsyncProxyFactory of(LayerAction<?, ?> syncAction,
                                 AsyncLayerAction<?, ?> asyncAction) {
    return of("proxy", syncAction, asyncAction);
  }

  <T> T protect(Class<T> serviceInterface, T target);
}
