package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.ProxyWrapper;
import eu.inqudium.core.pipeline.Wrapper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Async extension of {@link ProxyWrapper} that routes methods returning
 * {@link CompletionStage} through an {@link AsyncLayerAction}.
 *
 * <p>Overrides {@link #dispatchServiceMethod} to check the method's return type:
 * if it is assignable to {@link CompletionStage}, the call is dispatched through
 * {@link #executeAsyncChain}. All other methods fall through to the sync chain.</p>
 *
 * @since 0.4.0
 */
public class AsyncProxyWrapper extends ProxyWrapper {

  private final AsyncLayerAction<Void, Object> asyncAction;

  /**
   * Pre-resolved strategy for building the next step in the async chain.
   * Determined once at construction time based on the delegate's type.
   */
  private final Function<InternalAsyncExecutor<Void, Object>,
      InternalAsyncExecutor<Void, Object>> nextStepFactory;

  /**
   * Unified constructor — works for both real targets and inner ProxyWrapper delegates.
   * The next-step strategy is resolved from the delegate type: AsyncProxyWrapper inner
   * delegates to its async chain, plain ProxyWrapper adapts sync to async, and a
   * non-wrapper target uses the terminal directly.
   */
  @SuppressWarnings("unchecked")
  protected AsyncProxyWrapper(String name, Object delegate,
                              LayerAction<Void, Object> syncAction,
                              AsyncLayerAction<Void, Object> asyncAction) {
    super(name, delegate, syncAction);
    this.asyncAction = asyncAction;

    if (delegate instanceof AsyncProxyWrapper asyncInner) {
      this.nextStepFactory = terminal ->
          (cid, caid, a) -> asyncInner.executeAsyncChain(cid, caid, terminal);
    } else if (delegate instanceof ProxyWrapper syncInner) {
      this.nextStepFactory = terminal ->
          (cid, caid, a) -> (CompletionStage<Object>) syncInner.executeSyncChain(cid, caid,
              terminal::executeAsync);
    } else {
      this.nextStepFactory = Function.identity();
    }
  }

  /**
   * Creates an async-capable proxy. Detects existing pipeline proxies and stacks on top.
   */
  @SuppressWarnings("unchecked")
  public static <T> T createProxy(Class<T> serviceInterface, T target, String name,
                                  LayerAction<Void, Object> syncAction,
                                  AsyncLayerAction<Void, Object> asyncAction) {
    ProxyWrapper inner = resolveInner(target);
    Object delegate = (inner != null) ? inner : target;
    AsyncProxyWrapper handler = new AsyncProxyWrapper(name, delegate, syncAction, asyncAction);
    return (T) Proxy.newProxyInstance(
        serviceInterface.getClassLoader(),
        new Class<?>[]{serviceInterface, Wrapper.class},
        handler);
  }

  @Override
  protected Object dispatchServiceMethod(Method method, Object[] args) throws Throwable {
    if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
      long callId = generateCallId();
      return executeAsyncChain(chainId(), callId, buildAsyncTerminal(method, args));
    }
    return super.dispatchServiceMethod(method, args);
  }

  protected CompletionStage<Object> executeAsyncChain(long chainId, long callId,
                                                      InternalAsyncExecutor<Void, Object> terminal) {
    return asyncAction.executeAsync(chainId, callId, null, nextStepFactory.apply(terminal));
  }

  @SuppressWarnings("unchecked")
  protected InternalAsyncExecutor<Void, Object> buildAsyncTerminal(Method method, Object[] args) {
    return (chainId, callId, arg) -> {
      try {
        return (CompletionStage<Object>) method.invoke(realTarget(), args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw rethrow(e.getCause());
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    };
  }
}
