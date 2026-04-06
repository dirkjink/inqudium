package eu.inqudium.core.pipeline;

import java.lang.reflect.Method;

/**
 * SPI for pluggable dispatch strategies beyond the default sync chain.
 *
 * <p>Each extension handles a specific category of methods — typically identified by
 * return type (e.g. {@code CompletionStage} for async, {@code Publisher} for reactive).
 * Extensions are composed into a {@link ProxyWrapper} and maintain their own
 * independent chain walk, separate from other extensions.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>Creation:</b> The extension is created with its action (e.g. an async bulkhead)</li>
 *   <li><b>Linking:</b> When a {@link ProxyWrapper} wraps another, each extension receives
 *       the inner handler's extensions via {@link #linkInner} and returns a chained copy
 *       that delegates to the matching inner extension</li>
 *   <li><b>Dispatch:</b> At invocation time, the wrapper asks each extension via
 *       {@link #canHandle}; the first match receives the call via {@link #dispatch}</li>
 * </ol>
 *
 * @since 0.5.0
 */
public interface DispatchExtension {

  /**
   * Returns {@code true} if this extension handles the given method.
   */
  boolean canHandle(Method method);

  /**
   * Dispatches the method call through this extension's chain.
   */
  Object dispatch(long chainId, long callId, Method method, Object[] args, Object realTarget);

  /**
   * Creates a new instance of this extension that is chained to the matching
   * extension from the inner handler.
   *
   * @param innerExtensions the extensions from the inner handler (may be empty, never null)
   * @return a new extension instance linked to the inner chain
   */
  DispatchExtension linkInner(DispatchExtension[] innerExtensions);
}
