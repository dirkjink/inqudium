package eu.inqudium.core.pipeline;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;

/**
 * Dispatch extension for synchronous method calls.
 *
 * <p>Acts as the catch-all fallback — {@link #canHandle} always returns {@code true}.
 * When composing extensions in a {@link ProxyWrapper}, this extension should be
 * registered <strong>last</strong> so that more specific extensions (async, reactive)
 * get first chance to match.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ProxyWrapper.createProxy(OrderService.class, target, "bulkhead",
 *     new AsyncDispatchExtension(asyncBulkheadAction),  // matches CompletionStage
 *     new SyncDispatchExtension(syncBulkheadAction));   // catches everything else
 * }</pre>
 *
 * @since 0.5.0
 */
public class SyncDispatchExtension implements DispatchExtension {

  private final LayerAction<Void, Object> action;

  private final Function<InternalExecutor<Void, Object>,
      InternalExecutor<Void, Object>> nextStepFactory;

  /**
   * Creates an unlinked sync extension (no inner counterpart).
   */
  public SyncDispatchExtension(LayerAction<Void, Object> action) {
    this.action = action;
    this.nextStepFactory = Function.identity();
  }

  /**
   * Internal constructor with a resolved inner counterpart.
   */
  private SyncDispatchExtension(LayerAction<Void, Object> action,
                                SyncDispatchExtension inner) {
    this.action = action;
    this.nextStepFactory = (inner != null)
        ? terminal -> (cid, caid, a) -> inner.executeChain(cid, caid, terminal)
        : Function.identity();
  }

  // ======================== DispatchExtension SPI ========================

  /**
   * Always returns {@code true} — sync is the catch-all fallback.
   */
  @Override
  public boolean canHandle(Method method) {
    return true;
  }

  @Override
  public Object dispatch(long chainId, long callId,
                         Method method, Object[] args, Object realTarget) {
    return executeChain(chainId, callId, buildTerminal(method, args, realTarget));
  }

  @Override
  public DispatchExtension linkInner(List<DispatchExtension> innerExtensions) {
    SyncDispatchExtension inner = innerExtensions.stream()
        .filter(SyncDispatchExtension.class::isInstance)
        .map(SyncDispatchExtension.class::cast)
        .findFirst()
        .orElse(null);
    return new SyncDispatchExtension(this.action, inner);
  }

  // ======================== Sync chain walk ========================

  /**
   * Applies this layer's {@link LayerAction} and delegates to the next sync step.
   * Package-visible so linked inner extensions can be called during chain traversal.
   */
  Object executeChain(long chainId, long callId, InternalExecutor<Void, Object> terminal) {
    return action.execute(chainId, callId, null, nextStepFactory.apply(terminal));
  }

  // ======================== Terminal builder ========================

  private InternalExecutor<Void, Object> buildTerminal(Method method, Object[] args,
                                                       Object realTarget) {
    return (chainId, callId, arg) -> {
      try {
        return method.invoke(realTarget, args);
      } catch (InvocationTargetException e) {
        throw Throws.rethrow(e.getCause() != null ? e.getCause() : e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    };
  }
}
