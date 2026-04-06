package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.DispatchExtension;
import eu.inqudium.core.pipeline.Throws;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Dispatch extension for methods returning {@link CompletionStage}.
 *
 * <p>Plugs into {@link eu.inqudium.core.pipeline.ProxyWrapper ProxyWrapper} via the
 * {@link DispatchExtension} SPI. Maintains its own async chain walk, independent from
 * the sync chain.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ProxyWrapper.createProxy(OrderService.class, target, "bulkhead",
 *     new AsyncDispatchExtension(asyncBulkheadAction),
 *     new SyncDispatchExtension(syncBulkheadAction));
 * }</pre>
 *
 * @since 0.5.0
 */
public class AsyncDispatchExtension implements DispatchExtension {

  private final AsyncLayerAction<Void, Object> action;

  private final Function<InternalAsyncExecutor<Void, Object>,
      InternalAsyncExecutor<Void, Object>> nextStepFactory;

  /**
   * Creates an unlinked async extension (no inner counterpart).
   */
  public AsyncDispatchExtension(AsyncLayerAction<Void, Object> action) {
    this.action = action;
    this.nextStepFactory = Function.identity();
  }

  /**
   * Internal constructor with a resolved inner counterpart.
   */
  private AsyncDispatchExtension(AsyncLayerAction<Void, Object> action,
                                 AsyncDispatchExtension inner) {
    this.action = action;
    this.nextStepFactory = (inner != null)
        ? terminal -> (cid, caid, a) -> inner.executeChain(cid, caid, terminal)
        : Function.identity();
  }

  // ======================== DispatchExtension SPI ========================

  @Override
  public boolean canHandle(Method method) {
    return CompletionStage.class.isAssignableFrom(method.getReturnType());
  }

  @Override
  public Object dispatch(long chainId, long callId,
                         Method method, Object[] args, Object realTarget) {
    return executeChain(chainId, callId, buildTerminal(method, args, realTarget));
  }

  @Override
  public DispatchExtension linkInner(List<DispatchExtension> innerExtensions) {
    AsyncDispatchExtension inner = innerExtensions.stream()
        .filter(AsyncDispatchExtension.class::isInstance)
        .map(AsyncDispatchExtension.class::cast)
        .findFirst()
        .orElse(null);
    return new AsyncDispatchExtension(this.action, inner);
  }

  // ======================== Async chain walk ========================

  CompletionStage<Object> executeChain(long chainId, long callId,
                                       InternalAsyncExecutor<Void, Object> terminal) {
    return action.executeAsync(chainId, callId, null, nextStepFactory.apply(terminal));
  }

  // ======================== Terminal builder ========================

  @SuppressWarnings("unchecked")
  private InternalAsyncExecutor<Void, Object> buildTerminal(Method method, Object[] args,
                                                            Object realTarget) {
    return (chainId, callId, arg) -> {
      try {
        return (CompletionStage<Object>) method.invoke(realTarget, args);
      } catch (InvocationTargetException e) {
        throw Throws.rethrow(e.getCause() != null ? e.getCause() : e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    };
  }
}
