package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.AbstractBaseWrapper;
import eu.inqudium.core.pipeline.Wrapper;

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
 * <h3>{@code Object} method semantics</h3>
 * <p>{@code equals} and {@code hashCode} are delegated to the deep
 * {@link #realTarget()}, so two proxies wrapping the same target behave
 * correctly in collections. {@code toString} returns a descriptive
 * representation that includes the wrapper layer and the target's
 * {@code toString}.</p>
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

  public static void validateInterface(Class<?> type) {
    if (!type.isInterface()) {
      throw new IllegalArgumentException(
          "InqProxyFactory requires an interface, but received: " + type.getName());
    }
  }

  // ======================== Invoke dispatch ========================

  protected static AbstractProxyWrapper resolveInner(Object target) {
    if (Proxy.isProxyClass(target.getClass())) {
      InvocationHandler h = Proxy.getInvocationHandler(target);
      if (h instanceof AbstractProxyWrapper inner) {
        return inner;
      }
    }
    return null;
  }

  protected Object realTarget() {
    return realTarget;
  }

  // ======================== Utilities ========================

  @Override
  public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // Wrapper infrastructure methods execute on the handler itself
    if (isWrapperMethod(method)) {
      return method.invoke(this, args);
    }

    // Object identity methods delegate to the real target
    if (method.getDeclaringClass() == Object.class) {
      return handleObjectMethod(proxy, method, args);
    }

    return dispatchServiceMethod(method, args);
  }

  protected abstract Object dispatchServiceMethod(Method method, Object[] args) throws Throwable;

  // ======================== Object method handling ========================

  /**
   * Handles {@code equals}, {@code hashCode}, and {@code toString} so that
   * proxies behave correctly in collections and diagnostics.
   *
   * <ul>
   *   <li>{@code equals} — unwraps the other side (if it is also a proxy
   *       backed by an {@code AbstractProxyWrapper}) and compares the deep
   *       real targets via {@code Object.equals}.</li>
   *   <li>{@code hashCode} — delegates to the real target so that equal
   *       proxies produce the same hash code.</li>
   *   <li>{@code toString} — returns a descriptive representation including
   *       the layer description and the target's {@code toString}.</li>
   *   <li>All other {@code Object} methods (e.g. {@code getClass},
   *       {@code notify}) are invoked on the handler as before.</li>
   * </ul>
   */
  private Object handleObjectMethod(Object proxy, Method method, Object[] args) throws Throwable {
    return switch (method.getName()) {
      case "equals" -> handleEquals(args[0]);
      case "hashCode" -> realTarget.hashCode();
      case "toString" -> layerDescription() + " -> " + realTarget.toString();
      default -> method.invoke(this, args);
    };
  }

  /**
   * Two proxies are equal when they wrap the same real target.
   * A proxy is also equal to its own real target.
   */
  private boolean handleEquals(Object other) {
    if (other == null) {
      return false;
    }

    // Unwrap the other side if it is also one of our proxies
    Object otherTarget = other;
    if (Proxy.isProxyClass(other.getClass())) {
      InvocationHandler h = Proxy.getInvocationHandler(other);
      if (h instanceof AbstractProxyWrapper otherWrapper) {
        otherTarget = otherWrapper.realTarget;
      }
    } else if (other instanceof AbstractProxyWrapper otherWrapper) {
      otherTarget = otherWrapper.realTarget;
    }

    return realTarget.equals(otherTarget);
  }

  // ======================== Method classification ========================

  private boolean isWrapperMethod(Method method) {
    Class<?> declaringClass = method.getDeclaringClass();
    return Wrapper.class.isAssignableFrom(declaringClass);
  }
}
