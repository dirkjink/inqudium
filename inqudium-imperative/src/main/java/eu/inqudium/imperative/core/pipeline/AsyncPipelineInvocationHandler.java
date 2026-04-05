package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.PipelineInvocationHandler;
import eu.inqudium.core.pipeline.Wrapper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Async extension of {@link PipelineInvocationHandler} that routes methods returning
 * {@link CompletionStage} through an {@link AsyncLayerAction}.
 *
 * <p>Overrides {@link #dispatchServiceMethod} to check the method's return type:
 * if it is assignable to {@link CompletionStage}, the call is dispatched through
 * {@link #executeAsyncChain}. All other methods fall through to the sync chain
 * in the parent class.</p>
 *
 * <p>The next-step strategy (async inner, sync-to-async adapter, or direct terminal)
 * is resolved once at construction time — no {@code instanceof} checks on the hot path.</p>
 *
 * <p><b>Artifact note:</b> This class belongs in the imperative artifact
 * ({@code eu.inqudium.imperative.core.pipeline}), separate from the core sync pipeline.</p>
 *
 * @since 0.4.0
 */
public class AsyncPipelineInvocationHandler extends PipelineInvocationHandler {

  private final AsyncLayerAction<Void, Object> asyncAction;

  /**
   * Pre-resolved strategy for building the next step in the async chain.
   * Determined once at construction time based on the inner handler's type.
   * Accepts a per-invocation terminal and returns the next step for the async chain.
   */
  private final Function<InternalAsyncExecutor<Void, Object>,
      InternalAsyncExecutor<Void, Object>> nextStepFactory;

  /**
   * Wrapping a real target — no inner handler, terminal goes directly to asyncAction.
   */
  public AsyncPipelineInvocationHandler(String name, Object target,
                                        LayerAction<Void, Object> syncAction,
                                        AsyncLayerAction<Void, Object> asyncAction) {
    super(name, target, syncAction);
    this.asyncAction = asyncAction;
    this.nextStepFactory = Function.identity();
  }

  /**
   * Wrapping another handler — resolves next-step strategy based on inner handler type.
   */
  @SuppressWarnings("unchecked")
  public AsyncPipelineInvocationHandler(String name, PipelineInvocationHandler inner,
                                        LayerAction<Void, Object> syncAction,
                                        AsyncLayerAction<Void, Object> asyncAction) {
    super(name, inner, syncAction);
    this.asyncAction = asyncAction;

    if (inner instanceof AsyncPipelineInvocationHandler asyncInner) {
      // Inner has async — delegate to its async chain
      this.nextStepFactory = terminal ->
          (cid, caid, a) -> asyncInner.executeAsyncChain(cid, caid, terminal);
    } else {
      // Inner is sync-only — adapt its sync chain to produce a CompletionStage
      this.nextStepFactory = terminal ->
          (cid, caid, a) -> (CompletionStage<Object>) inner.executeSyncChain(cid, caid,
              terminal::executeAsync);
    }
  }

  /**
   * Creates an async-capable proxy. Detects existing pipeline proxies and stacks on top.
   */
  @SuppressWarnings("unchecked")
  public static <T> T createProxy(Class<T> serviceInterface, T target, String name,
                                  LayerAction<Void, Object> syncAction,
                                  AsyncLayerAction<Void, Object> asyncAction) {
    PipelineInvocationHandler inner = resolveInner(target);
    AsyncPipelineInvocationHandler handler = (inner != null)
        ? new AsyncPipelineInvocationHandler(name, inner, syncAction, asyncAction)
        : new AsyncPipelineInvocationHandler(name, target, syncAction, asyncAction);

    return (T) Proxy.newProxyInstance(
        serviceInterface.getClassLoader(),
        new Class<?>[]{serviceInterface, Wrapper.class},
        handler);
  }

  /**
   * Routes async methods through the async chain, sync methods through the parent's sync chain.
   */
  @Override
  protected Object dispatchServiceMethod(Method method, Object[] args) throws Throwable {
    if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
      long callId = generateCallId();
      return executeAsyncChain(chainId(), callId, buildAsyncTerminal(method, args));
    }
    return super.dispatchServiceMethod(method, args);
  }

  /**
   * Async chain walk — applies this layer's async action with the pre-resolved next step.
   * No instanceof checks, no branching — the strategy was determined at construction time.
   */
  protected CompletionStage<Object> executeAsyncChain(long chainId, long callId,
                                                      InternalAsyncExecutor<Void, Object> terminal) {
    return asyncAction.executeAsync(chainId, callId, null, nextStepFactory.apply(terminal));
  }

  /**
   * Builds the per-invocation async terminal step — the only allocation per async call.
   */
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
