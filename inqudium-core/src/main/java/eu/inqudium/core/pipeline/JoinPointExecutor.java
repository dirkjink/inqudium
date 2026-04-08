package eu.inqudium.core.pipeline;

/**
 * Functional interface representing a proxy execution point, such as a Spring AOP
 * {@code ProceedingJoinPoint} or a dynamic proxy invocation handler.
 *
 * <p>This abstraction decouples the wrapper pipeline from specific AOP frameworks.
 * By accepting a method reference like {@code pjp::proceed}, the
 * {@link JoinPointWrapper} can treat the proxy execution as just another layer
 * in the pipeline — no Spring dependency required in the core module.</p>
 *
 * <p>The {@code throws Throwable} clause mirrors the signature of
 * {@code ProceedingJoinPoint.proceed()}, ensuring that all exception types —
 * including checked exceptions — are propagated without loss. This is
 * intentionally broader than {@link java.util.concurrent.Callable}'s
 * {@code throws Exception}, because AOP join points can surface any
 * {@link Throwable} including {@link Error} subclasses.</p>
 *
 * <h3>Usage with Spring AOP</h3>
 * <pre>{@code
 * @Around("@annotation(MyAnnotation)")
 * public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *     // pjp::proceed matches the JoinPointExecutor functional interface
 *     JoinPointWrapper<Object> wrapper =
 *         new JoinPointWrapper<>("resilience", pjp::proceed, resilienceAction);
 *     return wrapper.proceed();
 * }
 * }</pre>
 *
 * @param <R> the return type of the proxy execution
 */
@FunctionalInterface
public interface JoinPointExecutor<R> {

  /**
   * Executes the proxied operation and returns its result.
   *
   * <p>When used with Spring AOP, this typically delegates to
   * {@code ProceedingJoinPoint.proceed()}. The broad {@code throws Throwable}
   * signature ensures that no exception type is lost during transport.</p>
   *
   * @return the result of the proxied execution
   * @throws Throwable any exception thrown by the underlying operation,
   *                   including checked exceptions and errors
   */
  R proceed() throws Throwable;
}
