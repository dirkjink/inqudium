package eu.inqudium.core.pipeline;

/**
 * Functional interface representing a proxy execution point, such as a Spring AOP
 * {@code ProceedingJoinPoint} or a dynamic proxy invocation handler.
 *
 * <p>This abstraction allows AOP join points to be wrapped in the same chain
 * architecture used by standard functional interfaces (Runnable, Supplier, etc.).
 * By accepting a method reference like {@code pjp::proceed}, the
 * {@link JoinPointWrapper} can treat the proxy execution as just another layer
 * in the pipeline.</p>
 *
 * <p>The {@code throws Throwable} clause mirrors the signature of
 * {@code ProceedingJoinPoint.proceed()}, ensuring that all exception types —
 * including checked exceptions — are propagated without loss.</p>
 *
 * @param <R> the return type of the proxy execution
 */
@FunctionalInterface
public interface JoinPointExecutor<R> {

  /**
   * Executes the proxied operation and returns its result.
   *
   * @return the result of the proxied execution
   * @throws Throwable any exception thrown by the underlying operation
   */
  R proceed() throws Throwable;
}
