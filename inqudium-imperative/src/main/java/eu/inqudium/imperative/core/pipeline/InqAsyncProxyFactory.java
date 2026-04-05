package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.InqProxyFactory;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.ProxyWrapper;
import eu.inqudium.core.pipeline.Wrapper;

import java.util.concurrent.CompletionStage;

/**
 * Factory for creating dynamic proxies that route method invocations through
 * both a sync {@link LayerAction} and an async {@link AsyncLayerAction}.
 *
 * <p>Extends {@link InqProxyFactory} with async routing: methods returning
 * {@link CompletionStage} (or subtypes like {@link java.util.concurrent.CompletableFuture})
 * are dispatched through the {@link AsyncLayerAction#executeAsync}, all other methods
 * go through the sync {@link LayerAction#execute}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * InqAsyncProxyFactory factory = InqAsyncProxyFactory.of("bulkhead", bulkhead, bulkhead);
 * PaymentService proxy = factory.protect(PaymentService.class, realService);
 *
 * proxy.charge(order);          // sync  → LayerAction.execute
 * proxy.chargeAsync(order);     // async → AsyncLayerAction.executeAsync
 *
 * // Chain-capable: nesting preserves IDs
 * PaymentService withRetry = retryAsyncFactory.protect(PaymentService.class, proxy);
 * }</pre>
 *
 * @since 0.4.0
 */
@FunctionalInterface
public interface InqAsyncProxyFactory extends InqProxyFactory {

  /**
   * Creates an async proxy factory with the given layer name, sync and async around-advice.
   *
   * @param name        the layer name (visible in {@link Wrapper#toStringHierarchy()})
   * @param syncAction  the around-advice for sync methods
   * @param asyncAction the around-advice for async methods (returning {@link CompletionStage})
   * @return an async proxy factory
   */
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
        return AsyncProxyWrapper.createProxy(serviceInterface, target, name, sync, async);
      }
    };
  }

  /**
   * Creates an async proxy factory with a default layer name.
   *
   * @param syncAction  the around-advice for sync methods
   * @param asyncAction the around-advice for async methods
   * @return an async proxy factory
   */
  static InqAsyncProxyFactory of(LayerAction<?, ?> syncAction,
                                 AsyncLayerAction<?, ?> asyncAction) {
    return of("proxy", syncAction, asyncAction);
  }

  <T> T protect(Class<T> serviceInterface, T target);
}
