package eu.inqudium.core.pipeline;

import java.lang.reflect.Method;
import java.util.List;

/**
 * SPI for pluggable dispatch strategies beyond the default sync chain.
 *
 * <p>Each extension handles a specific category of methods — typically identified by
 * return type (e.g. {@code CompletionStage} for async, {@code Publisher} for reactive).
 * Extensions are composed into a {@link ProxyWrapper} and maintain their own
 * independent chain walk, separate from the sync chain.</p>
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
 * <h3>Extensibility</h3>
 * <p>Adding a new dispatch mode (e.g. reactive) requires only a new
 * {@code DispatchExtension} implementation — {@code ProxyWrapper} remains unchanged.</p>
 *
 * @since 0.5.0
 */
public interface DispatchExtension {

  /**
   * Returns {@code true} if this extension handles the given method.
   * Typically based on return type (e.g. {@code CompletionStage.class.isAssignableFrom(...)}).
   */
  boolean canHandle(Method method);

  /**
   * Dispatches the method call through this extension's chain.
   *
   * @param chainId    the chain identifier
   * @param callId     the call identifier for this invocation
   * @param method     the method being invoked
   * @param args       the method arguments
   * @param realTarget the unwrapped target at the bottom of the chain
   * @return the result of the chain execution
   */
  Object dispatch(long chainId, long callId, Method method, Object[] args, Object realTarget);

  /**
   * Creates a new instance of this extension that is chained to the matching
   * extension from the inner handler. Called during {@link ProxyWrapper} construction.
   *
   * <p>The implementation searches {@code innerExtensions} for a compatible counterpart
   * (typically by type) and returns a copy that delegates to it. If no match is found,
   * the returned extension dispatches directly to its terminal.</p>
   *
   * @param innerExtensions the extensions from the inner handler (may be empty)
   * @return a new extension instance linked to the inner chain
   */
  DispatchExtension linkInner(List<DispatchExtension> innerExtensions);
}
