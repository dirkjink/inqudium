package eu.inqudium.core.pipeline;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Shared infrastructure for proxy-based wrappers.
 *
 * <p>Handles real-target resolution, infrastructure-method dispatch, and proxy
 * detection. All dispatch logic — sync, async, reactive — lives in composable
 * {@link DispatchExtension} implementations.</p>
 *
 * @since 0.5.0
 */
public abstract class AbstractProxyWrapper
    extends AbstractBaseWrapper<Object, AbstractProxyWrapper>
    implements InvocationHandler {

  private final Object realTarget;

  protected AbstractProxyWrapper(String name, Object delegate) {
    super(name, delegate);
    this.realTarget = (delegate instanceof AbstractProxyWrapper inner)
        ? inner.realTarget : delegate;
  }

  protected Object realTarget() {
    return realTarget;
  }

  // ======================== Invoke dispatch ========================

  @Override
  public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (isInfrastructureMethod(method)) {
      return method.invoke(this, args);
    }
    return dispatchServiceMethod(method, args);
  }

  protected abstract Object dispatchServiceMethod(Method method, Object[] args) throws Throwable;

  // ======================== Utilities ========================

  public static void validateInterface(Class<?> type) {
    if (!type.isInterface()) {
      throw new IllegalArgumentException(
          "InqProxyFactory requires an interface, but received: " + type.getName());
    }
  }

  protected static AbstractProxyWrapper resolveInner(Object target) {
    if (Proxy.isProxyClass(target.getClass())) {
      InvocationHandler h = Proxy.getInvocationHandler(target);
      if (h instanceof AbstractProxyWrapper inner) {
        return inner;
      }
    }
    return null;
  }

  private boolean isInfrastructureMethod(Method method) {
    Class<?> declaringClass = method.getDeclaringClass();
    return declaringClass == Object.class
        || Wrapper.class.isAssignableFrom(declaringClass);
  }
}
