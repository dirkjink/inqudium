package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.AbstractBaseWrapper;
import eu.inqudium.core.pipeline.Wrapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Shared infrastructure for proxy-based wrappers.
 *
 * <p>This abstract class serves as the bridge between the wrapper chain
 * infrastructure ({@link AbstractBaseWrapper}) and the JDK dynamic proxy
 * mechanism ({@link InvocationHandler}). It handles three key responsibilities:</p>
 *
 * <ol>
 *   <li><strong>Real-target resolution:</strong> When proxies are stacked
 *       (a proxy wrapping another proxy), this class resolves the deepest
 *       non-proxy target at construction time. This "real target" is used
 *       for {@code equals}/{@code hashCode} semantics and for the chain-walk
 *       optimization in dispatch extensions.</li>
 *   <li><strong>Infrastructure-method dispatch:</strong> Methods declared on
 *       the {@link Wrapper} interface (e.g. {@code inner()}, {@code chainId()})
 *       are dispatched to the invocation handler itself rather than the target.</li>
 *   <li><strong>Object-method handling:</strong> {@code equals}, {@code hashCode},
 *       and {@code toString} have well-defined semantics (see below), while
 *       other {@code Object} methods are handled appropriately.</li>
 * </ol>
 *
 * <h3>{@code Object} method semantics</h3>
 * <ul>
 *   <li>{@code equals} — two proxies are equal when they are the same wrapper
 *       subtype and wrap the same deep real target. A proxy is never equal to
 *       a bare (unwrapped) object, preserving the symmetry contract of
 *       {@code Object.equals()}.</li>
 *   <li>{@code hashCode} — delegates to the deep real target, so equal proxies
 *       produce the same hash code.</li>
 *   <li>{@code toString} — returns {@code "layerDescription -> realTarget.toString()"}.</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractProxyWrapper
    extends AbstractBaseWrapper<Object, AbstractProxyWrapper>
    implements InvocationHandler {

  /**
   * The deepest non-proxy target in the wrapper chain. Resolved once at
   * construction time by walking through nested {@code AbstractProxyWrapper}
   * delegates. Used for equals/hashCode semantics and chain-walk optimization.
   */
  private final Object realTarget;

  /**
   * Constructs a new proxy wrapper layer.
   *
   * <p>If the delegate is itself an {@code AbstractProxyWrapper}, the real target
   * is inherited from the inner wrapper (already resolved to the deepest level).
   * Otherwise, the delegate itself is the real target.</p>
   *
   * @param name     human-readable layer name for diagnostics
   * @param delegate the wrapped target — either another proxy wrapper or the real object
   */
  protected AbstractProxyWrapper(String name, Object delegate) {
    super(name, delegate);
    // Resolve the deep real target: if the delegate is already a proxy wrapper,
    // it has already resolved its own real target — just inherit it.
    // Otherwise, the delegate IS the real target.
    this.realTarget = (delegate instanceof AbstractProxyWrapper inner)
        ? inner.realTarget : delegate;
  }

  /**
   * Validates that the given type is an interface.
   *
   * <p>JDK dynamic proxies can only implement interfaces, not concrete classes.
   * This method provides a consistent, descriptive error message when a caller
   * accidentally passes a class instead of an interface.</p>
   *
   * @param type the type to validate
   * @throws IllegalArgumentException if the type is not an interface
   */
  public static void validateInterface(Class<?> type) {
    if (!type.isInterface()) {
      throw new IllegalArgumentException(
          "InqProxyFactory requires an interface, but received: " + type.getName());
    }
  }

  // ======================== Invoke dispatch ========================

  /**
   * Attempts to extract an {@code AbstractProxyWrapper} from a JDK proxy object.
   *
   * <p>If the target is a JDK proxy whose invocation handler is an
   * {@code AbstractProxyWrapper}, returns that handler. Otherwise returns {@code null}.
   * Used during proxy construction to detect and link inner proxy layers.</p>
   *
   * @param target the object to inspect
   * @return the inner {@code AbstractProxyWrapper}, or {@code null} if the target
   * is not a compatible proxy
   */
  protected static AbstractProxyWrapper resolveInner(Object target) {
    if (Proxy.isProxyClass(target.getClass())) {
      InvocationHandler h = Proxy.getInvocationHandler(target);
      if (h instanceof AbstractProxyWrapper inner) {
        return inner;
      }
    }
    return null;
  }

  /**
   * Returns the deepest non-proxy target in the wrapper chain.
   *
   * <p>This is the actual business object at the bottom of the proxy stack.
   * Used for equals/hashCode semantics and as the terminal invocation target
   * in the chain-walk optimization.</p>
   *
   * @return the real (non-proxy) target object
   */
  protected Object realTarget() {
    return realTarget;
  }

  // ======================== Utilities ========================

  /**
   * Central dispatch point for all method invocations on the JDK proxy.
   *
   * <p>Routes method calls into three categories:</p>
   * <ol>
   *   <li><strong>Wrapper infrastructure methods</strong> (declared on {@link Wrapper}
   *       or its supertypes): executed on the handler itself, giving the proxy
   *       access to chain metadata like {@code chainId()}, {@code inner()}, etc.</li>
   *   <li><strong>Object identity methods</strong> ({@code equals}, {@code hashCode},
   *       {@code toString}, etc.): handled with proxy-aware semantics via
   *       {@link #handleObjectMethod}.</li>
   *   <li><strong>Service methods</strong> (all other methods): delegated to the
   *       abstract {@link #dispatchServiceMethod} for extension-based dispatch.</li>
   * </ol>
   *
   * @param proxy  the JDK proxy instance that the method was invoked on
   * @param method the {@code Method} being invoked
   * @param args   the method arguments
   * @return the method's return value
   * @throws Throwable if the underlying method or dispatch logic throws
   */
  @Override
  public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // Category 1: Wrapper interface methods execute on the handler itself,
    // giving the proxy access to chainId(), inner(), layerDescription(), etc.
    if (isWrapperMethod(method)) {
      return method.invoke(this, args);
    }

    // Category 2: Object methods get special proxy-aware handling
    // (equals, hashCode, toString, etc.)
    if (method.getDeclaringClass() == Object.class) {
      return handleObjectMethod(proxy, method, args);
    }

    // Category 3: All remaining methods are service methods — dispatch
    // through the extension pipeline
    return dispatchServiceMethod(method, args);
  }

  /**
   * Dispatches a service method call through the extension pipeline.
   *
   * <p>Implemented by {@link ProxyWrapper} to iterate through registered
   * {@link DispatchExtension} instances and delegate to the first one
   * whose {@code canHandle(method)} returns {@code true}.</p>
   *
   * @param method the service method being invoked
   * @param args   the method arguments
   * @return the method's return value
   * @throws Throwable if the dispatch or underlying method throws
   */
  protected abstract Object dispatchServiceMethod(Method method, Object[] args) throws Throwable;

  // ======================== Object method handling ========================

  /**
   * Handles {@code Object} methods with proxy-aware semantics.
   *
   * <p>Dispatch table:</p>
   * <ul>
   *   <li>{@code equals} — unwraps the other side (if it is also a proxy backed by
   *       an {@code AbstractProxyWrapper}) and compares the deep real targets.
   *       See {@link #handleEquals} for the full contract.</li>
   *   <li>{@code hashCode} — delegates to the real target so that equal proxies
   *       produce the same hash code (consistent with equals).</li>
   *   <li>{@code toString} — returns a descriptive representation including the
   *       layer description and the target's toString.</li>
   *   <li>{@code wait}, {@code notify}, {@code notifyAll} — must operate on the
   *       proxy object itself (not the handler), because callers synchronize on
   *       the proxy reference.</li>
   *   <li>{@code getClass} — returns the proxy's class, not the handler's class,
   *       matching what callers expect from the proxy reference.</li>
   *   <li>All other {@code Object} methods — invoked on the handler as a fallback.</li>
   * </ul>
   */
  private Object handleObjectMethod(Object proxy, Method method, Object[] args) throws Throwable {
    return switch (method.getName()) {
      case "equals" -> handleEquals(args[0]);
      case "hashCode" -> realTarget.hashCode();
      case "toString" -> layerDescription() + " -> " + realTarget.toString();
      // Thread synchronization primitives must operate on the proxy itself,
      // not the handler, to match what callers synchronize on.
      case "wait", "notify", "notifyAll" -> method.invoke(proxy, args);
      // getClass must reflect the proxy's class, not the handler's.
      case "getClass" -> proxy.getClass();
      default -> method.invoke(this, args);
    };
  }

  /**
   * Implements proxy-aware equality with a strict contract.
   *
   * <p>The equality rules are designed to be consistent, symmetric, and
   * semantically meaningful:</p>
   * <ol>
   *   <li><strong>Identity fast path:</strong> {@code this == other} returns true immediately.</li>
   *   <li><strong>Null check:</strong> {@code null} is never equal.</li>
   *   <li><strong>Proxy extraction:</strong> If {@code other} is a JDK proxy, extract its
   *       {@code AbstractProxyWrapper} handler. If {@code other} is directly an
   *       {@code AbstractProxyWrapper}, use it as-is.</li>
   *   <li><strong>Non-proxy rejection:</strong> A proxy is never equal to a bare (unwrapped)
   *       object. This preserves the symmetry contract — if {@code proxy.equals(bare)} were
   *       true, then {@code bare.equals(proxy)} would need to be true as well, which is
   *       impossible since the bare object doesn't know about proxies.</li>
   *   <li><strong>Subtype check:</strong> Different wrapper subtypes (e.g. sync vs. async)
   *       are never equal, even when wrapping the same real target. This preserves the
   *       semantic distinction between wrapper kinds.</li>
   *   <li><strong>Real target comparison:</strong> Finally, delegates to the real target's
   *       own {@code equals()} method for the actual business-logic equality check.</li>
   * </ol>
   *
   * @param other the object to compare with
   * @return {@code true} if the other object is a proxy of the same type wrapping
   * an equal real target
   */
  private boolean handleEquals(Object other) {
    // 1. Identity check — fast path
    if (this == other) {
      return true;
    }
    if (other == null) {
      return false;
    }

    AbstractProxyWrapper otherWrapper = null;

    // 2. Extract the AbstractProxyWrapper from the other object.
    //    It could be a JDK proxy (extract handler) or a direct wrapper reference.
    if (Proxy.isProxyClass(other.getClass())) {
      InvocationHandler h = Proxy.getInvocationHandler(other);
      if (h instanceof AbstractProxyWrapper apw) {
        otherWrapper = apw;
      }
    } else if (other instanceof AbstractProxyWrapper apw) {
      otherWrapper = apw;
    }

    // 3. If 'other' is not a proxy wrapper, return false.
    //    A proxy must never claim to equal a bare object (symmetry contract).
    if (otherWrapper == null) {
      return false;
    }

    // 4. Different wrapper subtypes are never equal — even if they wrap
    //    the same real target, they have different dispatch semantics.
    if (this.getClass() != otherWrapper.getClass()) {
      return false;
    }

    // 5. Delegate to the real target's equals() for the actual comparison
    return this.realTarget.equals(otherWrapper.realTarget);
  }

  // ======================== Method classification ========================

  /**
   * Determines whether a method belongs to the {@link Wrapper} interface hierarchy.
   *
   * <p>Methods declared on {@code Wrapper} or any of its supertypes are classified
   * as "wrapper methods" and dispatched to the invocation handler itself (not
   * the target). This ensures that chain introspection methods like
   * {@code chainId()}, {@code inner()}, and {@code layerDescription()} work
   * correctly on the proxy.</p>
   *
   * @param method the method to classify
   * @return {@code true} if the method is declared on {@code Wrapper} or a supertype
   */
  private boolean isWrapperMethod(Method method) {
    Class<?> declaringClass = method.getDeclaringClass();
    // Check if the declaring class is Wrapper itself or any supertype of Wrapper.
    // This covers Wrapper's own methods plus any future supertypes.
    return Wrapper.class.isAssignableFrom(declaringClass);
  }
}
