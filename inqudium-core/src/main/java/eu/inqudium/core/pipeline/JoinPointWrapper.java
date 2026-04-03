package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionException;

/**
 * Wrapper for dynamic proxies and Spring AOP JoinPoints.
 * Implements {@link ProxyExecution} to allow homogeneous chaining.
 * Since the proxy encapsulates its own arguments, the chain argument type is Void.
 *
 * <p>
 * This method creates a {@link JoinPointWrapper} around the intercepted join point.
 * By doing so, the AOP execution becomes a formal layer in the static wrapper stack.
 * This allows the join point to participate in the unique {@code callId} propagation
 * and the {@code toStringHierarchy()} visualization.
 * </p>
 *
 * <p><strong>Example Aspect Implementation:</strong></p>
 * <pre>{@code
 * @Around("@annotation(MyCustomAnnotation)")
 * public Object traceHierarchy(ProceedingJoinPoint pjp) throws Throwable {
 *     JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
 *         pjp.getSignature().toShortString(),
 *         pjp::proceed
 *     );
 *     return wrapper.proceed();
 * }
 * }</pre>
 *
 * <p><strong>Key Benefits:</strong></p>
 * <ul>
 *   <li><b>ID Consistency:</b> Every sub-call within the join point can access
 *       the same {@code callId} via the stack.</li>
 *   <li><b>Full Transparency:</b> The {@code toStringHierarchy()} will list
 *       the AOP proxy as a named layer.</li>
 *   <li><b>Exception Safety:</b> Throwable exceptions from {@code proceed()}
 *       are passed through transparently via {@link CompletionException}.</li>
 * </ul>
 *
 * @param <R> the return type of the join point execution
 */
public class JoinPointWrapper<R>
    extends BaseWrapper<ProxyExecution<R>, Void, R, JoinPointWrapper<R>>
    implements ProxyExecution<R> {

  public JoinPointWrapper(String name, ProxyExecution<R> delegate) {
    super(name, delegate);
  }

  @Override
  public R proceed() throws Throwable {
    try {
      return initiateChain(null);
    } catch (CompletionException e) {
      // Only unwrap exceptions that were explicitly wrapped by invokeCore
      throw e.getCause();
    }
  }

  @Override
  protected R invokeCore(Void argument) {
    try {
      return getDelegate().proceed();
    } catch (RuntimeException e) {
      // Let runtime exceptions pass through unwrapped
      throw e;
    } catch (Error e) {
      // Let errors pass through unwrapped
      throw e;
    } catch (Throwable t) {
      // Wrap only checked exceptions/throwables for transport through the InternalExecutor chain
      throw new CompletionException(t);
    }
  }

  @Override
  protected void handleLayer(String callId, Void argument) {
    // Optional: logging context updates using the callId
  }
}
