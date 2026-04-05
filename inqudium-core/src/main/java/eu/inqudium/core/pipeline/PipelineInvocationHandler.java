package eu.inqudium.core.pipeline;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Sync invocation handler that extends {@link BaseWrapper} — inheriting chain metadata,
 * ID management, hierarchy visualization, and the {@link Wrapper} interface.
 *
 * <p>When stacked on top of another {@code PipelineInvocationHandler}, the inner handler
 * is passed as the delegate to {@link BaseWrapper}'s constructor, which automatically
 * inherits {@code chainId} and {@code callIdCounter}.</p>
 *
 * <p>Subclassed by the async variant in the imperative artifact to add
 * {@link java.util.concurrent.CompletionStage} routing.</p>
 *
 * <h3>Why this does not use {@link JoinPointExecutor} / {@link JoinPointWrapper}</h3>
 * <p>{@link JoinPointWrapper} binds its {@link JoinPointExecutor} delegate once at construction
 * time — ideal for AOP where the target method is known upfront. A dynamic proxy, however,
 * receives a different {@code Method} and {@code args} on every {@code invoke()} call.
 * Using {@code JoinPointWrapper} would require creating a new {@code ProxyExecution} lambda
 * and a new {@code JoinPointWrapper} instance per invocation — only to use them once and
 * discard them immediately.</p>
 *
 * <p>Instead, this handler creates only a lightweight {@link InternalExecutor} lambda as
 * the per-invocation terminal and passes it through the existing chain via
 * {@link #executeSyncChain}. No wrapper objects, no {@code initiateChain()} traversal,
 * no redundant {@code callId} generation — the proxy controls the ID lifecycle directly
 * in {@link #invoke}.</p>
 *
 * @since 0.4.0
 */
public class PipelineInvocationHandler
    extends BaseWrapper<Object, Void, Object, PipelineInvocationHandler>
    implements InvocationHandler {

  /**
   * Placeholder core execution — never called. The actual terminal is built
   * per invocation and passed through the chain walk.
   */
  protected static final InternalExecutor<Void, Object> PROXY_CORE =
      (chainId, callId, arg) -> {
        throw new UnsupportedOperationException(
            "Direct execute() not supported on proxy handlers — use invoke()");
      };

  private final Object realTarget;
  private final LayerAction<Void, Object> syncAction;

  /**
   * Wrapping a real target — creates new chain metadata.
   */
  public PipelineInvocationHandler(String name, Object target,
                                   LayerAction<Void, Object> syncAction) {
    super(name, target, PROXY_CORE);
    this.realTarget = target;
    this.syncAction = syncAction;
  }

  /**
   * Wrapping another handler — BaseWrapper inherits chainId and callIdCounter.
   */
  public PipelineInvocationHandler(String name, PipelineInvocationHandler inner,
                                   LayerAction<Void, Object> syncAction) {
    super(name, inner, PROXY_CORE);
    this.realTarget = inner.realTarget;
    this.syncAction = syncAction;
  }

  @SuppressWarnings("unchecked")
  protected static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
    throw (E) t;
  }

  public static void validateInterface(Class<?> type) {
    if (!type.isInterface()) {
      throw new IllegalArgumentException(
          "InqProxyFactory requires an interface, but received: " + type.getName());
    }
  }

  /**
   * Creates a sync-only proxy. Detects existing pipeline proxies and stacks on top.
   */
  @SuppressWarnings("unchecked")
  public static <T> T createProxy(Class<T> serviceInterface, T target, String name,
                                  LayerAction<Void, Object> syncAction) {
    PipelineInvocationHandler handler = resolveHandler(target, name, syncAction);
    return (T) Proxy.newProxyInstance(
        serviceInterface.getClassLoader(),
        new Class<?>[]{serviceInterface, Wrapper.class},
        handler);
  }

  /**
   * Resolves the inner handler if the target is a pipeline proxy, then creates a new handler.
   * Protected so that async subclasses can reuse the detection logic.
   */
  protected static PipelineInvocationHandler resolveInner(Object target) {
    if (Proxy.isProxyClass(target.getClass())) {
      InvocationHandler h = Proxy.getInvocationHandler(target);
      if (h instanceof PipelineInvocationHandler inner) {
        return inner;
      }
    }
    return null;
  }

  private static PipelineInvocationHandler resolveHandler(Object target, String name,
                                                          LayerAction<Void, Object> syncAction) {
    PipelineInvocationHandler inner = resolveInner(target);
    return (inner != null)
        ? new PipelineInvocationHandler(name, inner, syncAction)
        : new PipelineInvocationHandler(name, target, syncAction);
  }

  // ======================== Infrastructure dispatch ========================

  /**
   * The unwrapped real target at the bottom of the chain.
   */
  protected Object realTarget() {
    return realTarget;
  }

  @Override
  public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (isInfrastructureMethod(method)) {
      return handleInfrastructureMethod(method, args);
    }
    return dispatchServiceMethod(method, args);
  }

  // ======================== Utilities ========================

  /**
   * Dispatches a service method call. Override in subclasses to add async routing.
   * Called after infrastructure methods (Wrapper, Object) have been handled.
   */
  protected Object dispatchServiceMethod(Method method, Object[] args) throws Throwable {
    long callId = generateCallId();
    return executeSyncChain(chainId(), callId, buildSyncTerminal(method, args));
  }

  /**
   * Recursive sync chain walk — mirrors {@link BaseWrapper#execute}.
   * Each handler applies its LayerAction, with {@code next} pointing to the
   * inner handler's chain walk (or the terminal at the bottom).
   *
   * <p>Public visibility is required because the async subclass in the imperative
   * artifact calls this method on inner handlers typed as {@code PipelineInvocationHandler}
   * — Java's protected access rules do not permit cross-package access through a
   * parent-class reference.</p>
   */
  public final Object executeSyncChain(long chainId, long callId,
                                       InternalExecutor<Void, Object> terminal) {
    PipelineInvocationHandler inner = inner();
    InternalExecutor<Void, Object> next = (inner != null)
        ? (cid, caid, a) -> inner.executeSyncChain(cid, caid, terminal)
        : terminal;
    return syncAction.execute(chainId, callId, null, next);
  }

  /**
   * Builds the per-invocation terminal step that calls the real target method.
   * This is the only object created per invocation — it replaces what would be
   * a {@link JoinPointExecutor} delegate in {@link JoinPointWrapper}, but without
   * the wrapper object overhead.
   */
  protected InternalExecutor<Void, Object> buildSyncTerminal(Method method, Object[] args) {
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

  private boolean isInfrastructureMethod(Method method) {
    if (method.getDeclaringClass() == Object.class) return true;
    if (method.getParameterCount() == 0) {
      return switch (method.getName()) {
        case "chainId", "currentCallId", "layerDescription", "inner", "toStringHierarchy" -> true;
        default -> false;
      };
    }
    return false;
  }

  private Object handleInfrastructureMethod(Method method, Object[] args) throws Throwable {
    if (method.getParameterCount() == 0) {
      switch (method.getName()) {
        case "currentCallId" -> {
          return currentCallId();
        }
        case "chainId" -> {
          return chainId();
        }
        case "layerDescription" -> {
          return layerDescription();
        }
        case "inner" -> {
          return inner();
        }
        case "toStringHierarchy" -> {
          return toStringHierarchy();
        }
      }
    }
    // Object methods (toString, hashCode, equals)
    return method.invoke(this, args);
  }
}
