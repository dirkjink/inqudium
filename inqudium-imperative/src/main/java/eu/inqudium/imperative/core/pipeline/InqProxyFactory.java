package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.StandaloneIdGenerator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletionStage;

/**
 * Factory for creating dynamic proxies that wrap service method invocations
 * through pipeline decorators.
 *
 * <p>Every method call on the returned proxy flows through the decorator's
 * around-advice — no wrapper objects are created per invocation, only a single
 * {@link InternalExecutor} (or {@link InternalAsyncExecutor}) lambda.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // From a sync decorator (all methods go through LayerAction.execute)
 * InqProxyFactory factory = InqProxyFactory.fromSync(bulkhead);
 * PaymentService protected = factory.protect(PaymentService.class, realService);
 * protected.charge(order);  // → bulkhead.execute → realService.charge(order)
 *
 * // From sync + async decorators (routes by return type)
 * InqProxyFactory factory = InqProxyFactory.from(bulkhead, bulkhead);
 * PaymentService protected = factory.protect(PaymentService.class, realService);
 * protected.charge(order);                  // sync  → LayerAction.execute
 * protected.chargeAsync(order);             // async → AsyncLayerAction.executeAsync
 * }</pre>
 *
 * <h3>Async Routing</h3>
 * <p>When an async decorator is provided, methods whose return type is assignable to
 * {@link CompletionStage} are automatically routed through
 * {@link AsyncLayerAction#executeAsync}. All other methods go through
 * {@link LayerAction#execute}.</p>
 *
 * @since 0.4.0
 */
@FunctionalInterface
public interface InqProxyFactory {

  /**
   * Creates a dynamic proxy that wraps every method invocation on the target
   * through the decorator's around-advice.
   *
   * @param serviceInterface the interface to proxy (must be an interface)
   * @param target           the real implementation to delegate to
   * @param <T>              the service interface type
   * @return a proxy that applies the decorator's logic on every method call
   * @throws IllegalArgumentException if {@code serviceInterface} is not an interface
   */
  <T> T protect(Class<T> serviceInterface, T target);

  /**
   * Creates a factory that routes all method calls through a sync {@link LayerAction}.
   *
   * @param asyncAction the around-advice for async methods
   * @return a proxy factory
   */
  static InqProxyFactory fromAsync(AsyncLayerAction<Void, Object> asyncAction) {
    return new InqProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        validateInterface(serviceInterface);
        return createProxy(serviceInterface, target, null, asyncAction);
      }
    };
  }

  /**
   * Creates a factory that routes all method calls through a sync {@link LayerAction}.
   *
   * @param action the around-advice to apply on every method call
   * @return a proxy factory
   */
  static InqProxyFactory fromSync(LayerAction<Void, Object> action) {
    return new InqProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        validateInterface(serviceInterface);
        return createProxy(serviceInterface, target, action, null);
      }
    };
  }

  /**
   * Creates a factory that routes sync methods through a {@link LayerAction}
   * and async methods (returning {@link CompletionStage}) through an
   * {@link AsyncLayerAction}.
   *
   * @param syncAction  the around-advice for sync methods
   * @param asyncAction the around-advice for async methods
   * @return a proxy factory
   */
  static InqProxyFactory from(LayerAction<Void, Object> syncAction,
                               AsyncLayerAction<Void, Object> asyncAction) {
    return new InqProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        validateInterface(serviceInterface);
        return createProxy(serviceInterface, target, syncAction, asyncAction);
      }
    };
  }

  // ======================== Internal ========================

  private static void validateInterface(Class<?> type) {
    if (!type.isInterface()) {
      throw new IllegalArgumentException(
          "InqProxyFactory requires an interface, but received: " + type.getName());
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T createProxy(Class<T> serviceInterface,
                                   T target,
                                   LayerAction<Void, Object> syncAction,
                                   AsyncLayerAction<Void, Object> asyncAction) {

    InvocationHandler handler = (proxy, method, args) -> {

      // Route async methods through the async decorator when available
      if (asyncAction != null
          && CompletionStage.class.isAssignableFrom(method.getReturnType())) {
        return asyncAction.executeAsync(
            StandaloneIdGenerator.nextChainId(),
            StandaloneIdGenerator.nextCallId(),
            null,
            (chainId, callId, arg) -> {
              try {
                return (CompletionStage<Object>) method.invoke(target, args);
              } catch (InvocationTargetException e) {
                throw rethrow(e.getCause());
              } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
              }
            }
        );
      }

      // Sync path
      return syncAction.execute(
          StandaloneIdGenerator.nextChainId(),
          StandaloneIdGenerator.nextCallId(),
          null,
          (chainId, callId, arg) -> {
            try {
              return method.invoke(target, args);
            } catch (InvocationTargetException e) {
              throw rethrow(e.getCause());
            } catch (IllegalAccessException e) {
              throw new RuntimeException(e);
            }
          }
      );
    };

    return (T) Proxy.newProxyInstance(
        serviceInterface.getClassLoader(),
        new Class<?>[]{ serviceInterface },
        handler
    );
  }

  /**
   * Rethrows any Throwable as an unchecked exception without wrapping.
   * Uses the compiler trick to bypass checked exception enforcement.
   */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
    throw (E) t;
  }
}
