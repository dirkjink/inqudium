package eu.inqudium.core.pipeline;

/**
 * Internal contract for propagating a call through the wrapper chain.
 *
 * <p>This is the fundamental building block of chain traversal. Each layer in the
 * wrapper chain holds a reference to the next {@code InternalExecutor}, which may
 * be either another wrapper layer or the terminal "core execution" lambda that
 * invokes the actual delegate.</p>
 *
 * <p>The interface deliberately uses primitive {@code long} parameters for chain ID
 * and call ID to achieve zero-allocation tracing on the hot path — no boxing,
 * no wrapper objects, no string formatting until explicitly needed.</p>
 *
 * <p>This interface is package-private in spirit (though technically public for
 * cross-package access within the framework). Application code should interact
 * with the wrapper chain through the public functional interfaces
 * ({@link Runnable}, {@link java.util.function.Supplier}, etc.) rather than
 * calling {@code execute} directly.</p>
 *
 * @param <A> the argument type passed through the chain (e.g. {@code Void} for
 *            {@link Runnable}/{@link java.util.function.Supplier}, or the input
 *            type for {@link java.util.function.Function})
 * @param <R> the return type produced by the chain (e.g. {@code Void} for
 *            {@link Runnable}, or the output type for
 *            {@link java.util.function.Supplier}/{@link java.util.function.Function})
 */
public interface InternalExecutor<A, R> {

  /**
   * Executes this layer's logic and propagates to the next layer in the chain.
   *
   * <p>Implementations typically delegate to a {@link LayerAction} which decides
   * when and whether to call the next step. The terminal implementation at the
   * end of the chain invokes the actual delegate (e.g. calls
   * {@code delegate.run()}, {@code delegate.get()}, etc.).</p>
   *
   * @param chainId  identifies the wrapper chain — shared across all layers,
   *                 useful for correlating log entries from the same chain
   * @param callId   identifies this particular invocation — unique per call,
   *                 incremented by the outermost wrapper before chain traversal
   * @param argument the argument flowing through the chain; {@code null} for
   *                 argument-free interfaces like {@link Runnable} and
   *                 {@link java.util.function.Supplier}
   * @return the result of the innermost delegate's execution, potentially
   *         modified by intermediate layers
   */
  R execute(long chainId, long callId, A argument);
}
