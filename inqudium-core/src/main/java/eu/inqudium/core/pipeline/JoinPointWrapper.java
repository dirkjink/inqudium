package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionException;

/**
 * Wrapper for dynamic proxies and Spring AOP join points.
 *
 * <p>This wrapper integrates AOP proxy executions into the same chain architecture used
 * by the standard functional wrappers. By wrapping a {@link ProxyExecution} — typically
 * a method reference to {@code ProceedingJoinPoint::proceed} — the AOP execution becomes
 * a first-class layer in the pipeline.</p>
 *
 * <p>Since a proxy execution encapsulates its own arguments internally, the chain
 * argument type is {@code Void}. The same two-phase exception strategy as
 * {@link CallableWrapper} is used for checked exceptions.</p>
 *
 * <h3>Usage in a Spring Aspect</h3>
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
 * @param <R> the return type of the join point execution
 */
public class JoinPointWrapper<R>
    extends BaseWrapper<ProxyExecution<R>, Void, R, JoinPointWrapper<R>>
    implements ProxyExecution<R> {

  public JoinPointWrapper(String name, ProxyExecution<R> delegate) {
    super(name, delegate, (callId, arg) -> {
      try {
        return delegate.proceed();
      } catch (RuntimeException | Error e) {
        // Runtime exceptions and errors pass through unwrapped
        throw e;
      } catch (Throwable t) {
        // Checked exceptions/throwables need wrapping for transport, unwrapped in proceed()
        throw new CompletionException(t);
      }
    });
  }

  @Override
  public R proceed() throws Throwable {
    try {
      return initiateChain(null);
    } catch (RuntimeException e) {
      if (e instanceof CompletionException) {
        throw e.getCause();
      }
      throw e;
    }
  }
}
