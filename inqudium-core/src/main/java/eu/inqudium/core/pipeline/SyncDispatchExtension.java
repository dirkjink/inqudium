package eu.inqudium.core.pipeline;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Dispatch extension for synchronous method calls.
 *
 * <p>Acts as the catch-all fallback — {@link #canHandle} always returns {@code true}.
 * When composing extensions in a {@link ProxyWrapper}, this extension should be
 * registered <strong>last</strong> so that more specific extensions get first match.</p>
 *
 * @since 0.5.0
 */
public class SyncDispatchExtension implements DispatchExtension {

  private final LayerAction<Void, Object> action;

  private final Function<InternalExecutor<Void, Object>,
      InternalExecutor<Void, Object>> nextStepFactory;

  public SyncDispatchExtension(LayerAction<Void, Object> action) {
    this.action = action;
    this.nextStepFactory = Function.identity();
  }

  private SyncDispatchExtension(LayerAction<Void, Object> action,
                                SyncDispatchExtension inner) {
    this.action = action;
    this.nextStepFactory = (inner != null)
        ? terminal -> (cid, caid, a) -> inner.executeChain(cid, caid, terminal)
        : Function.identity();
  }

  // ======================== DispatchExtension SPI ========================

  private static SyncDispatchExtension findInner(DispatchExtension[] extensions) {
    for (int i = 0; i < extensions.length; i++) {
      if (extensions[i] instanceof SyncDispatchExtension sync) {
        return sync;
      }
    }
    return null;
  }

  private static InternalExecutor<Void, Object> buildTerminal(Method method, Object[] args,
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

  @Override
  public boolean canHandle(Method method) {
    return true;
  }

  // ======================== Chain walk ========================

  @Override
  public Object dispatch(long chainId, long callId,
                         Method method, Object[] args, Object realTarget) {
    return executeChain(chainId, callId, buildTerminal(method, args, realTarget));
  }

  // ======================== Internal ========================

  @Override
  public DispatchExtension linkInner(DispatchExtension[] innerExtensions) {
    return new SyncDispatchExtension(this.action, findInner(innerExtensions));
  }

  Object executeChain(long chainId, long callId, InternalExecutor<Void, Object> terminal) {
    return action.execute(chainId, callId, null, nextStepFactory.apply(terminal));
  }
}
