package eu.inqudium.core.pipeline;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Sync invocation handler that extends {@link BaseWrapper} — inheriting chain metadata,
 * ID management, hierarchy visualization, and the {@link Wrapper} interface.
 *
 * <p>When stacked on top of another {@code ProxyWrapper}, the inner handler
 * is passed as the delegate to {@link BaseWrapper}'s constructor, which automatically
 * inherits {@code chainId} and {@code callIdCounter}.</p>
 *
 * @since 0.4.0
 */
public class ProxyWrapper
    extends BaseWrapper<Object, Void, Object, ProxyWrapper>
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
   * Unified constructor — works for both real targets and inner ProxyWrapper delegates.
   * When wrapping another ProxyWrapper, BaseWrapper inherits chainId and callIdCounter;
   * realTarget is resolved from the inner handler.
   */
  protected ProxyWrapper(String name, Object delegate,
                         LayerAction<Void, Object> syncAction) {
    super(name, delegate, PROXY_CORE);
    this.realTarget = (delegate instanceof ProxyWrapper inner) ? inner.realTarget : delegate;
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
    ProxyWrapper inner = resolveInner(target);
    Object delegate = (inner != null) ? inner : target;
    ProxyWrapper handler = new ProxyWrapper(name, delegate, syncAction);
    return (T) Proxy.newProxyInstance(
        serviceInterface.getClassLoader(),
        new Class<?>[]{serviceInterface, Wrapper.class},
        handler);
  }

  /**
   * Resolves the inner handler if the target is a pipeline proxy.
   * Protected so that async subclasses can reuse the detection logic.
   */
  protected static ProxyWrapper resolveInner(Object target) {
    if (Proxy.isProxyClass(target.getClass())) {
      InvocationHandler h = Proxy.getInvocationHandler(target);
      if (h instanceof ProxyWrapper inner) {
        return inner;
      }
    }
    return null;
  }

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

  /**
   * Dispatches a service method call. Override in subclasses to add async routing.
   */
  protected Object dispatchServiceMethod(Method method, Object[] args) throws Throwable {
    long callId = generateCallId();
    return executeSyncChain(chainId(), callId, buildSyncTerminal(method, args));
  }

  /**
   * Recursive sync chain walk — mirrors {@link BaseWrapper#execute}.
   *
   * <p>Public visibility is required because the async subclass in the imperative
   * artifact calls this method on inner handlers typed as {@code ProxyWrapper}.</p>
   */
  public final Object executeSyncChain(long chainId, long callId,
                                       InternalExecutor<Void, Object> terminal) {
    ProxyWrapper inner = inner();
    InternalExecutor<Void, Object> next = (inner != null)
        ? (cid, caid, a) -> inner.executeSyncChain(cid, caid, terminal)
        : terminal;
    return syncAction.execute(chainId, callId, null, next);
  }

  /**
   * Builds the per-invocation terminal step that calls the real target method.
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
      return switch (method.getName()) {
        case "currentCallId"     -> currentCallId();
        case "chainId"           -> chainId();
        case "layerDescription"  -> layerDescription();
        case "inner"             -> inner();
        case "toStringHierarchy" -> toStringHierarchy();
        default                  -> method.invoke(this, args);
      };
    }
    // Object methods (toString, hashCode, equals)
    return method.invoke(this, args);
  }
}
