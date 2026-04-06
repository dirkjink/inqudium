package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.Throws;
import eu.inqudium.core.pipeline.proxy.DispatchExtension;
import eu.inqudium.core.pipeline.proxy.MethodHandleCache;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Dispatch extension for methods returning {@link CompletionStage}.
 *
 * <p>Plugs into {@link eu.inqudium.core.pipeline.proxy.ProxyWrapper ProxyWrapper} via the
 * {@link DispatchExtension} SPI. Maintains its own async chain walk, independent
 * from other extensions.</p>
 *
 * <h3>Chain-walk optimisation</h3>
 * <p>When {@link #linkInner} finds a type-compatible {@code AsyncDispatchExtension}
 * in the inner proxy, a direct chain walk is wired and the terminal invokes the
 * deep {@code realTarget}. When no counterpart exists, the terminal invokes the
 * proxy target provided by {@link eu.inqudium.core.pipeline.proxy.ProxyWrapper}, ensuring
 * the inner proxy's extensions are still executed.</p>
 *
 * @since 0.5.0
 */
public class AsyncDispatchExtension implements DispatchExtension {

  private final AsyncLayerAction<Void, Object> action;

  private final Function<InternalAsyncExecutor<Void, Object>,
      InternalAsyncExecutor<Void, Object>> nextStepFactory;

  /**
   * When non-null, overrides the target passed to {@link #dispatch} for
   * the terminal invocation. Set only when this extension was successfully
   * linked with an inner {@code AsyncDispatchExtension}.
   */
  private final Object overrideTarget;

  /**
   * Per-extension handle cache — avoids a global singleton.
   */
  private final MethodHandleCache handleCache;

  // ======================== Public constructor (root / standalone) ========================

  public AsyncDispatchExtension(AsyncLayerAction<Void, Object> action) {
    this.action = action;
    this.nextStepFactory = Function.identity();
    this.overrideTarget = null;
    this.handleCache = new MethodHandleCache();
  }

  // ======================== Internal constructors ========================

  /**
   * Linked constructor — wires a direct chain walk and uses realTarget
   * as terminal override. Inherits the outer extension's handle cache.
   */
  private AsyncDispatchExtension(AsyncLayerAction<Void, Object> action,
                                 AsyncDispatchExtension inner,
                                 Object realTarget,
                                 MethodHandleCache handleCache) {
    this.action = action;
    this.nextStepFactory = (inner != null)
        ? terminal -> (cid, caid, a) -> inner.executeChain(cid, caid, terminal)
        : Function.identity();
    this.overrideTarget = realTarget;
    this.handleCache = handleCache;
  }

  // ======================== Helpers ========================

  private static AsyncDispatchExtension findInner(DispatchExtension[] extensions) {
    for (int i = 0; i < extensions.length; i++) {
      if (extensions[i] instanceof AsyncDispatchExtension async) {
        return async;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private InternalAsyncExecutor<Void, Object> buildTerminal(Method method, Object[] args,
                                                            Object target) {
    return (chainId, callId, arg) -> {
      try {
        Object result = handleCache.invoke(target, method, args);
        if (result == null) {
          throw new IllegalStateException(
              "Method " + method.getName() + " returned null, expected a CompletionStage. "
                  + "Async-dispatched methods must never return null.");
        }
        if (!(result instanceof CompletionStage)) {
          throw new IllegalStateException(
              "Method " + method.getName() + " returned "
                  + result.getClass().getName() + ", expected a CompletionStage. "
                  + "This method should not be routed through AsyncDispatchExtension.");
        }
        return (CompletionStage<Object>) result;
      } catch (Throwable e) {
        throw handleException(method, e);
      }
    };
  }

  // ======================== DispatchExtension SPI ========================

  @Override
  public boolean canHandle(Method method) {
    return CompletionStage.class.isAssignableFrom(method.getReturnType());
  }

  @Override
  public Object dispatch(long chainId, long callId,
                         Method method, Object[] args, Object target) {
    Object effectiveTarget = (overrideTarget != null) ? overrideTarget : target;
    return executeChain(chainId, callId, buildTerminal(method, args, effectiveTarget));
  }

  // ======================== Chain walk ========================

  @Override
  public DispatchExtension linkInner(DispatchExtension[] innerExtensions,
                                     Object realTarget) {
    AsyncDispatchExtension inner = findInner(innerExtensions);
    if (inner != null) {
      return new AsyncDispatchExtension(this.action, inner, realTarget, this.handleCache);
    }
    return new AsyncDispatchExtension(this.action, null, null, this.handleCache);
  }

  @Override
  public DispatchExtension linkInner(DispatchExtension[] innerExtensions) {
    return linkInner(innerExtensions, null);
  }

  // ======================== Internal ========================

  CompletionStage<Object> executeChain(long chainId, long callId,
                                       InternalAsyncExecutor<Void, Object> terminal) {
    return action.executeAsync(chainId, callId, null, nextStepFactory.apply(terminal));
  }
}
