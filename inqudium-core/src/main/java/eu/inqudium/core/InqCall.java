package eu.inqudium.core;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Context-carrying wrapper for a call through the resilience pipeline.
 *
 * <p>Wraps a {@link Callable} rather than a {@code Supplier} so that checked
 * exceptions flow naturally through the decoration chain without intermediate
 * wrapping. The conversion to unchecked exceptions happens exactly once — at the
 * boundary where the pipeline returns a {@code Supplier} to the caller.
 *
 * <h2>callId semantics</h2>
 * <ul>
 *   <li><strong>Pipeline mode</strong> ({@link eu.inqudium.core.pipeline.InqPipeline}):
 *       The pipeline generates a callId and passes it through all decorators.
 *       All events and exceptions share this callId for end-to-end correlation.</li>
 *   <li><strong>Standalone mode</strong> ({@code decorateCallable}, {@code decorateSupplier},
 *       {@code decorateRunnable}): No callId is generated. The callId is
 *       {@link InqCallIdGenerator#NONE}, signaling that this call is not
 *       pipeline-correlated. Standalone methods are intended for single-element
 *       use only — composition of multiple elements is only supported via
 *       {@link eu.inqudium.core.pipeline.InqPipeline}.</li>
 * </ul>
 *
 * @param callId   the unique call identifier, or {@link InqCallIdGenerator#NONE} for standalone use
 * @param callable the operation to execute
 * @param <T>      the result type
 * @since 0.1.0
 */
public record InqCall<T>(String callId, Callable<T> callable) {

  public InqCall {
    Objects.requireNonNull(callable, "callable must not be null");
  }

  /**
   * Creates a new call with the given callId and callable.
   *
   * @param callId   the call identifier ({@link InqCallIdGenerator#NONE} for standalone use)
   * @param callable the operation
   * @param <T>      the result type
   * @return a new InqCall
   */
  public static <T> InqCall<T> of(String callId, Callable<T> callable) {
    return new InqCall<>(callId, callable);
  }

  /**
   * Creates a new call without a callId (standalone mode).
   *
   * @param callable the operation
   * @param <T>      the result type
   * @return a new InqCall with null callId
   */
  public static <T> InqCall<T> standalone(Callable<T> callable) {
    return new InqCall<>(InqCallIdGenerator.NONE, callable);
  }

  /**
   * Creates a new call with the same callId but a different callable.
   *
   * <p>Used by decorators to wrap the callable while preserving the callId:
   * <pre>{@code
   * return call.withCallable(() -> {
   *     acquirePermit(call.callId());
   *     return call.callable().call();
   * });
   * }</pre>
   *
   * @param newCallable the decorated callable
   * @return a new InqCall with the same callId
   */
  public InqCall<T> withCallable(Callable<T> newCallable) {
    return new InqCall<>(this.callId, newCallable);
  }

  /**
   * Executes the callable and returns the result.
   *
   * @return the result of the call
   * @throws Exception if the callable throws
   */
  public T execute() throws Exception {
    return callable.call();
  }
}
