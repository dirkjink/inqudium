package eu.inqudium.core.pipeline;

import java.util.concurrent.CompletionException;

/**
 * Wrapper for dynamic proxies and Spring AOP join points.
 *
 * <p>This wrapper integrates AOP proxy executions into the same chain architecture used
 * by the standard functional wrappers ({@link RunnableWrapper}, {@link SupplierWrapper},
 * etc.). By wrapping a {@link ProxyExecution} — typically a method reference to
 * {@code ProceedingJoinPoint::proceed} — the AOP execution becomes a first-class layer
 * in the pipeline, participating in call ID propagation, hierarchy visualization, and
 * all other cross-cutting concerns.</p>
 *
 * <p>Since a proxy execution encapsulates its own arguments internally, the chain
 * argument type is {@code Void}.</p>
 *
 * <h3>Exception Handling</h3>
 * <p>{@code ProceedingJoinPoint.proceed()} throws {@code Throwable}, which the
 * internal {@link InternalExecutor} chain cannot propagate directly (it only supports
 * unchecked exceptions). The same two-phase strategy as {@link CallableWrapper} is used:
 * {@link #invokeCore} wraps checked exceptions in {@link CompletionException},
 * and {@link #proceed()} unwraps them to restore the original exception type.
 * {@link RuntimeException} and {@link Error} pass through without wrapping.</p>
 *
 * <h3>Usage in a Spring Aspect</h3>
 * <pre>{@code
 * @Around("@annotation(MyCustomAnnotation)")
 * public Object traceHierarchy(ProceedingJoinPoint pjp) throws Throwable {
 *     // Wrap the join point execution as a named layer in the pipeline
 *     JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
 *         pjp.getSignature().toShortString(),
 *         pjp::proceed
 *     );
 *
 *     // Initiates the chain — all layers see the same callId,
 *     // and toStringHierarchy() includes this AOP layer by name
 *     return wrapper.proceed();
 * }
 * }</pre>
 *
 * <h3>Key Benefits</h3>
 * <ul>
 *   <li><strong>ID Consistency:</strong> Every sub-call within the join point can
 *       access the same {@code callId}, enabling end-to-end tracing.</li>
 *   <li><strong>Full Transparency:</strong> The {@link #toStringHierarchy()} output
 *       lists the AOP proxy as a named layer (e.g. "OrderService.placeOrder()").</li>
 *   <li><strong>Exception Safety:</strong> All exception types — including checked
 *       exceptions and {@link Throwable} — are transported through the chain and
 *       re-thrown with their original type via {@link CompletionException}.</li>
 * </ul>
 *
 * @param <R> the return type of the join point execution
 */
public class JoinPointWrapper<R>
    extends BaseWrapper<ProxyExecution<R>, Void, R, JoinPointWrapper<R>>
    implements ProxyExecution<R> {

  /**
   * Creates a new wrapper layer around the given {@link ProxyExecution}.
   *
   * @param name     a descriptive name for this layer (typically the method signature,
   *                 e.g. {@code pjp.getSignature().toShortString()})
   * @param delegate the proxy execution to wrap (another wrapper or the actual join point)
   */
  public JoinPointWrapper(String name, ProxyExecution<R> delegate) {
    super(name, delegate);
  }

  /**
   * Implements {@link ProxyExecution#proceed()} by initiating the wrapper chain
   * and restoring the original exception type on the way out.
   *
   * <p>If the core delegate throws a checked exception or a non-standard
   * {@link Throwable}, it arrives here as a {@link CompletionException} (wrapped by
   * {@link #invokeCore}). This method extracts the original cause and re-throws it,
   * preserving the exception contract of the underlying proxy. All other
   * {@link RuntimeException}s are re-thrown as-is.</p>
   *
   * @return the result of the proxied execution
   * @throws Throwable the original exception from the delegate, if any
   */
  @Override
  public R proceed() throws Throwable {
    try {
      // Void argument type — pass null to start the chain
      return initiateChain(null);
    } catch (RuntimeException e) {
      if (e instanceof CompletionException) {
        // Unwrap the checked exception/throwable that was wrapped by invokeCore
        throw e.getCause();
      }
      // All other RuntimeExceptions pass through unchanged
      throw e;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Invokes the core delegate's {@code proceed()} method. Exception handling strategy:</p>
   * <ul>
   *   <li>{@link RuntimeException} — re-thrown directly (no wrapping needed)</li>
   *   <li>{@link Error} — re-thrown directly (must never be swallowed)</li>
   *   <li>Checked {@link Throwable} — wrapped in {@link CompletionException} for
   *       transport through the unchecked-only {@link InternalExecutor} chain</li>
   * </ul>
   */
  @Override
  protected R invokeCore(Void argument) {
    try {
      return getDelegate().proceed();
    } catch (RuntimeException e) {
      // Runtime exceptions propagate naturally through the chain
      throw e;
    } catch (Error e) {
      // Errors (OutOfMemoryError, StackOverflowError, etc.) must never be wrapped
      throw e;
    } catch (Throwable t) {
      // Checked exceptions/throwables need wrapping for transport, unwrapped in proceed()
      throw new CompletionException(t);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Override this method in a subclass to add cross-cutting behavior around
   * the AOP execution, such as logging context updates, MDC propagation, or
   * performance metrics.</p>
   */
  @Override
  protected void handleLayer(String callId, Void argument) {
    // No-op by default — extend and override to add behavior
  }
}
