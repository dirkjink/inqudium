package eu.inqudium.core.pipeline;

import eu.inqudium.core.*;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqRuntimeException;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Decoration contract for resilience elements.
 *
 * <p>Provides two decoration modes:
 * <ul>
 *   <li><strong>Pipeline mode</strong> ({@link #decorate(InqCall)}): For composing multiple
 *       elements into a single decoration chain via {@link InqPipeline}. The pipeline
 *       generates a callId and passes it through all decorators via {@link InqCall}
 *       (ADR-022). <strong>This is the only supported way to compose multiple elements.</strong></li>
 *   <li><strong>Standalone mode</strong> ({@link #decorateCallable}, {@link #decorateSupplier},
 *       {@link #decorateRunnable}): For using a single element without a pipeline.
 *       No callId is generated — standalone calls are not pipeline-correlated.</li>
 * </ul>
 *
 * <h2>Composition: use InqPipeline, not manual nesting</h2>
 * <pre>{@code
 * // ✓ Correct — pipeline generates one shared callId
 * Supplier<Result> resilient = InqPipeline.of(() -> service.call())
 *     .shield(circuitBreaker)
 *     .shield(retry)
 *     .decorate();
 *
 * // ✗ UNSUPPORTED — each element has no callId, no correlation
 * Supplier<Result> broken = cb.decorateSupplier(
 *     () -> retry.executeSupplier(
 *         () -> service.call()));
 * }</pre>
 *
 * <h2>Implementation contract</h2>
 * <p>Element implementations must provide only two methods:
 * <ul>
 *   <li>{@link #decorate(InqCall)} — the element-specific resilience logic</li>
 *   <li>{@link #getConfig()} — configuration access for logging</li>
 * </ul>
 * <p>All standalone methods ({@code decorateCallable}, {@code decorateSupplier},
 * {@code decorateRunnable}) and all execute methods are provided as defaults.
 *
 * @since 0.1.0
 */
public interface InqDecorator extends InqElement {

  // ── Configuration access ──

  /**
   * Returns the element's configuration.
   *
   * <p>Used by the default {@link #decorateCallable(Callable)} to access
   * the SLF4J logger for error logging.
   *
   * @return the element configuration
   */
  InqConfig getConfig();

  // ── Pipeline mode ──

  /**
   * Wraps a call with this element's resilience logic, preserving the callId.
   *
   * <p>Used by {@link InqPipeline} to compose multiple elements into a single
   * decoration chain. The {@link InqCall} carries the shared callId — each
   * element reads {@code call.callId()} for event correlation.
   *
   * <p>This is the only method that element implementations must provide.
   * All standalone decoration methods delegate to this method.
   *
   * @param call the call to decorate (carries the shared callId)
   * @param <T>  the result type
   * @return a decorated call with the same callId
   */
  <T> InqCall<T> decorate(InqCall<T> call);

  // ── Standalone mode — single element only ──

  /**
   * Decorates a callable for standalone (single-element) use.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements — it generates a shared callId
   * and passes it through the decoration chain. Standalone decoration does not
   * generate a callId.
   *
   * <p>This is the Supplier boundary — checked exceptions from the {@link Callable}
   * are wrapped in {@link InqRuntimeException}. Runtime exceptions are logged with
   * element context before being rethrown. {@link InqException} subclasses (circuit
   * breaker open, bulkhead full, etc.) are rethrown without logging because they
   * represent expected element behavior.
   *
   * @param callable the callable to decorate
   * @param <T>      the result type
   * @return a decorated supplier (checked exceptions wrapped in InqRuntimeException)
   */
  default <T> Supplier<T> decorateCallable(Callable<T> callable) {
    return () -> {
      var call = InqCall.standalone(callable);
      try {
        return decorate(call).execute();
      } catch (InqException ie) {
        // Expected element behavior (CB open, BH full, etc.) — rethrow without logging
        throw ie;
      } catch (RuntimeException re) {
        getConfig().getLogger().error("{} '{}': {}",
            getElementType(), getName(), re.toString());
        throw re;
      } catch (Exception e) {
        getConfig().getLogger().error("{} '{}': {}",
            getElementType(), getName(), e.toString());
        throw new InqRuntimeException(InqCallIdGenerator.NONE, getName(), getElementType(), e);
      }
    };
  }

  /**
   * Decorates a supplier for standalone (single-element) use.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param supplier the supplier to decorate
   * @param <T>      the result type
   * @return a decorated supplier
   */
  default <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
    return decorateCallable(supplier::get);
  }

  /**
   * Decorates a runnable for standalone (single-element) use.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param runnable the runnable to decorate
   * @return a decorated runnable
   */
  default Runnable decorateRunnable(Runnable runnable) {
    Supplier<Void> decorated = decorateCallable(() -> {
      runnable.run();
      return null;
    });
    return decorated::get;
  }

  // ── Invocation methods — decorate once, call with different arguments ──

  /**
   * Decorates a single-argument invocation for standalone use.
   *
   * <p>This is the <strong>recommended pattern</strong> for operations that take
   * arguments at runtime. Decorate once, then call with different arguments:
   * <pre>{@code
   * Invocation<String, Payment> resilientCharge =
   *     cb.decorateInvocation(paymentService::charge);
   *
   * Payment p1 = resilientCharge.invoke("order-1");
   * Payment p2 = resilientCharge.invoke("order-2");
   * }</pre>
   *
   * <p>Optimized path — no array allocation per call.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param invocation the operation to decorate
   * @param <A>        the argument type
   * @param <T>        the result type
   * @return a decorated invocation
   */
  default <A, T> Invocation<A, T> decorateInvocation(Invocation<A, T> invocation) {
    return arg -> decorateCallable(() -> invocation.invoke(arg)).get();
  }

  /**
   * Decorates a two-argument invocation for standalone use.
   *
   * <p>Optimized path — no array allocation per call.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param invocation the operation to decorate
   * @param <A1>       the first argument type
   * @param <A2>       the second argument type
   * @param <T>        the result type
   * @return a decorated invocation
   */
  default <A1, A2, T> Invocation2<A1, A2, T> decorateInvocation(Invocation2<A1, A2, T> invocation) {
    return (arg1, arg2) -> decorateCallable(() -> invocation.invoke(arg1, arg2)).get();
  }

  /**
   * Decorates a three-argument invocation for standalone use.
   *
   * <p>Optimized path — no array allocation per call.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param invocation the operation to decorate
   * @param <A1>       the first argument type
   * @param <A2>       the second argument type
   * @param <A3>       the third argument type
   * @param <T>        the result type
   * @return a decorated invocation
   */
  default <A1, A2, A3, T> Invocation3<A1, A2, A3, T> decorateInvocation(Invocation3<A1, A2, A3, T> invocation) {
    return (arg1, arg2, arg3) -> decorateCallable(() -> invocation.invoke(arg1, arg2, arg3)).get();
  }

  /**
   * Decorates an array-based invocation for standalone use.
   *
   * <p>This is the base decoration — all other invocation types can delegate
   * to this via their {@code toArray()} / {@code asArray()} conversion methods.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param invocation the operation to decorate
   * @param <T>        the result type
   * @return a decorated invocation
   */
  default <T> InvocationArray<T> decorateInvocation(InvocationArray<T> invocation) {
    return args -> decorateCallable(() -> invocation.invoke(args)).get();
  }

  /**
   * Decorates a varargs invocation for standalone use.
   *
   * <p>Delegates to {@link #decorateInvocation(InvocationArray)} via
   * {@link InvocationVarargs#asArray()}.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param invocation the operation to decorate
   * @param <T>        the result type
   * @return a decorated invocation
   */
  default <T> InvocationVarargs<T> decorateInvocation(InvocationVarargs<T> invocation) {
    InvocationArray<T> decorated = decorateInvocation(invocation.asArray());
    return InvocationVarargs.fromArray(decorated);
  }

  // ── Execute methods — decorate and immediately invoke ──

  /**
   * Decorates and immediately executes a callable.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param callable the callable to execute
   * @param <T>      the result type
   * @return the result
   */
  default <T> T executeCallable(Callable<T> callable) {
    return decorateCallable(callable).get();
  }

  /**
   * Decorates and immediately executes a supplier.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param supplier the supplier to execute
   * @param <T>      the result type
   * @return the result
   */
  default <T> T executeSupplier(Supplier<T> supplier) {
    return decorateSupplier(supplier).get();
  }

  /**
   * Decorates and immediately executes a runnable.
   *
   * <p><strong>Composition of multiple elements is not supported via this method.</strong>
   * Use {@link InqPipeline} to compose elements.
   *
   * @param runnable the runnable to execute
   */
  default void executeRunnable(Runnable runnable) {
    decorateRunnable(runnable).run();
  }
}
