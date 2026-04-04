package eu.inqudium.core.pipeline;

/**
 * Internal contract for propagating a call through the wrapper chain.
 *
 * <p>Package-private. Enables the recursive delegation inside {@link BaseWrapper#executeWithId}:
 * each layer checks whether its delegate also implements {@code InternalExecutor} and,
 * if so, forwards the call with the same {@code callId}.</p>
 *
 * @param <A> the argument type passed through the chain
 * @param <R> the return type produced by the chain
 */
public interface InternalExecutor<A, R> {

  /**
   * Executes this layer's logic and propagates the call to the next inner layer.
   *
   * @param callId   a unique identifier for this particular invocation (primitive, zero-allocation)
   * @param argument the argument to pass through the chain
   * @return the result of the innermost delegate's execution
   */
  R executeWithId(long callId, A argument);
}
