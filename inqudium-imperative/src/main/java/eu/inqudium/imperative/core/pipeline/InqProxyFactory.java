package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.BaseWrapper;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.Wrapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletionStage;

/**
 * Factory for creating dynamic proxies that wrap service method invocations
 * through pipeline decorators.
 *
 * <h3>Chain-capable proxies</h3>
 * <p>Proxies created by this factory implement the {@link Wrapper} interface and are
 * chain-aware. The {@link PipelineInvocationHandler} extends {@link BaseWrapper},
 * so all chain mechanics (chainId inheritance, shared callIdCounter, hierarchy
 * visualization) come for free:</p>
 * <pre>{@code
 * PaymentService withBulkhead = bulkheadFactory.protect(PaymentService.class, real);
 * PaymentService withRetry    = retryFactory.protect(PaymentService.class, withBulkhead);
 *
 * // Chain traversal with shared IDs — same pattern as wrapper chains
 * withRetry.charge(order);
 * // → retry.execute(chainId=47, callId=1) → bulkhead.execute(chainId=47, callId=1) → real
 *
 * // Hierarchy visualization via Wrapper interface
 * Wrapper<?> chain = (Wrapper<?>) withRetry;
 * System.out.println(chain.toStringHierarchy());
 * // Chain-ID: 47
 * //   └─ retry
 * //       └─ bulkhead
 * }</pre>
 *
 * @since 0.4.0
 */
@FunctionalInterface
public interface InqProxyFactory {

  /**
   * Creates a factory that routes all method calls through a sync {@link LayerAction}.
   */
  static InqProxyFactory fromSync(String name, LayerAction<?, ?> action) {
    @SuppressWarnings("unchecked")
    LayerAction<Void, Object> sync = (LayerAction<Void, Object>) action;
    return new InqProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        validateInterface(serviceInterface);
        return createProxy(serviceInterface, target, name, sync, null);
      }
    };
  }

  /**
   * Creates a sync-only factory with a default layer name.
   */
  static InqProxyFactory fromSync(LayerAction<?, ?> action) {
    return fromSync("proxy", action);
  }

  /**
   * Creates a factory that routes sync methods through a {@link LayerAction}
   * and async methods (returning {@link CompletionStage}) through an
   * {@link AsyncLayerAction}.
   */
  static InqProxyFactory from(String name, LayerAction<?, ?> syncAction,
                              AsyncLayerAction<?, ?> asyncAction) {
    @SuppressWarnings("unchecked")
    LayerAction<Void, Object> sync = (LayerAction<Void, Object>) syncAction;
    @SuppressWarnings("unchecked")
    AsyncLayerAction<Void, Object> async = (AsyncLayerAction<Void, Object>) asyncAction;
    return new InqProxyFactory() {
      @Override
      public <T> T protect(Class<T> serviceInterface, T target) {
        validateInterface(serviceInterface);
        return createProxy(serviceInterface, target, name, sync, async);
      }
    };
  }

  /**
   * Creates a sync+async factory with a default layer name.
   */
  static InqProxyFactory from(LayerAction<?, ?> syncAction,
                              AsyncLayerAction<?, ?> asyncAction) {
    return from("proxy", syncAction, asyncAction);
  }

  private static void validateInterface(Class<?> type) {
    if (!type.isInterface()) {
      throw new IllegalArgumentException(
          "InqProxyFactory requires an interface, but received: " + type.getName());
    }
  }

  // ======================== Internal ========================

  @SuppressWarnings("unchecked")
  private static <T> T createProxy(Class<T> serviceInterface, T target, String name,
                                   LayerAction<Void, Object> syncAction,
                                   AsyncLayerAction<Void, Object> asyncAction) {

    PipelineInvocationHandler handler;

    // Detect existing pipeline proxy — stack on top (like BaseWrapper detects inner wrappers)
    if (Proxy.isProxyClass(target.getClass())) {
      InvocationHandler h = Proxy.getInvocationHandler(target);
      if (h instanceof PipelineInvocationHandler inner) {
        handler = new PipelineInvocationHandler(name, inner, syncAction, asyncAction);
      } else {
        handler = new PipelineInvocationHandler(name, target, syncAction, asyncAction);
      }
    } else {
      handler = new PipelineInvocationHandler(name, target, syncAction, asyncAction);
    }

    // Proxy implements the service interface AND Wrapper for chain visualization
    return (T) Proxy.newProxyInstance(
        serviceInterface.getClassLoader(),
        new Class<?>[]{serviceInterface, Wrapper.class},
        handler);
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
    throw (E) t;
  }

  /**
   * Creates a dynamic proxy that wraps every method invocation on the target
   * through the decorator's around-advice.
   *
   * <p>The returned proxy implements both the service interface and {@link Wrapper}.
   * If the target is itself a pipeline proxy, the new handler is stacked on top —
   * inheriting the inner handler's chainId and callIdCounter.</p>
   *
   * @param serviceInterface the interface to proxy (must be an interface)
   * @param target           the real implementation (or another pipeline proxy)
   * @param <T>              the service interface type
   * @return a chain-aware proxy implementing both {@code T} and {@link Wrapper}
   */
  <T> T protect(Class<T> serviceInterface, T target);

  // ======================== Pipeline Invocation Handler ========================

  /**
   * Invocation handler that extends {@link BaseWrapper} — inheriting chain metadata,
   * ID management, hierarchy visualization, and the {@link Wrapper} interface.
   *
   * <p>When stacked on top of another {@code PipelineInvocationHandler}, the inner handler
   * is passed as the delegate to {@link BaseWrapper}'s constructor, which automatically
   * detects it as a {@link BaseWrapper} and inherits {@code chainId} and
   * {@code callIdCounter}.</p>
   *
   * <p>The actual proxy dispatch uses {@link #executeSyncChain} / {@link #executeAsyncChain}
   * instead of {@link BaseWrapper#execute}, because the terminal step
   * ({@code method.invoke}) is per-invocation and must be passed through the chain.</p>
   */
  final class PipelineInvocationHandler
      extends BaseWrapper<Object, Void, Object, PipelineInvocationHandler>
      implements InvocationHandler {

    /**
     * Placeholder core execution — never called. The actual terminal is built
     * per invocation in {@link #invoke} and passed through the chain walk.
     */
    private static final InternalExecutor<Void, Object> PROXY_CORE =
        (chainId, callId, arg) -> {
          throw new UnsupportedOperationException(
              "Direct execute() not supported on proxy handlers — use invoke()");
        };

    private final Object realTarget;
    private final LayerAction<Void, Object> syncAction;
    private final AsyncLayerAction<Void, Object> asyncAction;

    /**
     * Wrapping a real target — creates new chain metadata.
     */
    PipelineInvocationHandler(String name, Object target,
                              LayerAction<Void, Object> syncAction,
                              AsyncLayerAction<Void, Object> asyncAction) {
      super(name, target, PROXY_CORE);
      this.realTarget = target;
      this.syncAction = syncAction;
      this.asyncAction = asyncAction;
    }

    /**
     * Wrapping another handler — BaseWrapper inherits chainId and callIdCounter.
     */
    PipelineInvocationHandler(String name, PipelineInvocationHandler inner,
                              LayerAction<Void, Object> syncAction,
                              AsyncLayerAction<Void, Object> asyncAction) {
      super(name, inner, PROXY_CORE);
      this.realTarget = inner.realTarget;
      this.syncAction = syncAction;
      this.asyncAction = asyncAction;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

      // Delegate Wrapper interface methods to this handler (which IS a Wrapper via BaseWrapper)
      if (method.getParameterCount() == 0) {
        switch (method.getName()) {
          case "getChainId" -> {
            return getChainId();
          }
          case "getLayerDescription" -> {
            return getLayerDescription();
          }
          case "getInner" -> {
            return getInner();
          }
          case "toStringHierarchy" -> {
            return toStringHierarchy();
          }
        }
      }

      // Delegate Object methods (toString, hashCode, equals)
      if (method.getDeclaringClass() == Object.class) {
        return method.invoke(this, args);
      }

      // ── Proxy dispatch ──
      long callId = generateCallId();

      if (asyncAction != null
          && CompletionStage.class.isAssignableFrom(method.getReturnType())) {
        return executeAsyncChain(getChainId(), callId, buildAsyncTerminal(method, args));
      }
      return executeSyncChain(getChainId(), callId, buildSyncTerminal(method, args));
    }

    /**
     * Recursive sync chain walk — mirrors {@link BaseWrapper#execute}.
     * Each handler applies its LayerAction, with {@code next} pointing to the
     * inner handler's chain walk (or the terminal at the bottom).
     */
    Object executeSyncChain(long chainId, long callId,
                            InternalExecutor<Void, Object> terminal) {
      PipelineInvocationHandler inner = getInner();
      InternalExecutor<Void, Object> next = (inner != null)
          ? (cid, caid, a) -> inner.executeSyncChain(cid, caid, terminal)
          : terminal;
      return syncAction.execute(chainId, callId, null, next);
    }

    /**
     * Recursive async chain walk — same pattern, returns CompletionStage.
     */
    @SuppressWarnings("unchecked")
    CompletionStage<Object> executeAsyncChain(long chainId, long callId,
                                              InternalAsyncExecutor<Void, Object> terminal) {
      if (asyncAction != null) {
        InternalAsyncExecutor<Void, Object> next = (inner() != null)
            ? (cid, caid, a) -> inner().executeAsyncChain(cid, caid, terminal)
            : terminal;
        return asyncAction.executeAsync(chainId, callId, null, next);
      }
      // Fallback: adapt sync action for async return type
      InternalExecutor<Void, Object> syncNext = (inner() != null)
          ? (cid, caid, a) -> (CompletionStage<Object>) inner().executeSyncChain(cid, caid,
          (c2, ca2, a2) -> terminal.executeAsync(c2, ca2, a2))
          : (cid, caid, a) -> terminal.executeAsync(cid, caid, a);
      return (CompletionStage<Object>) syncAction.execute(chainId, callId, null, syncNext);
    }

    /**
     * Typed accessor to avoid cast noise in chain walk.
     */
    private PipelineInvocationHandler inner() {
      return getInner();
    }

    private InternalExecutor<Void, Object> buildSyncTerminal(Method method, Object[] args) {
      return (chainId, callId, arg) -> {
        try {
          return method.invoke(realTarget, args);
        } catch (InvocationTargetException e) {
          throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      };
    }

    @SuppressWarnings("unchecked")
    private InternalAsyncExecutor<Void, Object> buildAsyncTerminal(Method method, Object[] args) {
      return (chainId, callId, arg) -> {
        try {
          return (CompletionStage<Object>) method.invoke(realTarget, args);
        } catch (InvocationTargetException e) {
          throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      };
    }
  }
}
