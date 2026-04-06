package eu.inqudium.core.pipeline;

import java.lang.reflect.Method;

/**
 * SPI for pluggable dispatch strategies inside a {@link ProxyWrapper}.
 *
 * <p>Extensions are checked in registration order; the first whose
 * {@link #canHandle} returns {@code true} receives the call.</p>
 *
 * @since 0.5.0
 */
public interface DispatchExtension {

  /**
   * Returns {@code true} if this extension can dispatch the given method.
   */
  boolean canHandle(Method method);

  /**
   * Dispatches the method call through this extension's logic.
   *
   * @param chainId    the chain identifier
   * @param callId     the per-call identifier
   * @param method     the service method being invoked
   * @param args       the method arguments
   * @param target     the invocation target for the terminal step
   * @return the method result
   */
  Object dispatch(long chainId, long callId,
                  Method method, Object[] args, Object target) throws Throwable;

  /**
   * Links this extension with matching inner extensions for chain-walk
   * optimisation.
   *
   * <p>Implementations that find a type-compatible counterpart in
   * {@code innerExtensions} should wire a direct chain walk and use
   * {@code realTarget} for the terminal invocation (skipping intermediate
   * proxy re-entry).</p>
   *
   * <p>Implementations that do <em>not</em> find a match should return
   * themselves unchanged — the framework will pass the delegate proxy as
   * the invocation target at dispatch time, ensuring that the inner proxy's
   * extensions are still invoked.</p>
   *
   * @param innerExtensions the extensions registered on the inner proxy
   * @param realTarget      the deepest non-proxy target; used as terminal
   *                        override when a type-compatible chain link exists
   * @return a (possibly new) extension instance wired into the inner chain
   */
  default DispatchExtension linkInner(DispatchExtension[] innerExtensions,
                                      Object realTarget) {
    // Backward-compatible default: delegate to legacy overload
    return linkInner(innerExtensions);
  }

  /**
   * Legacy linking without real-target override.
   *
   * <p>Kept for backward compatibility. New extensions should override
   * {@link #linkInner(DispatchExtension[], Object)} instead.</p>
   */
  default DispatchExtension linkInner(DispatchExtension[] innerExtensions) {
    return this;
  }
}
