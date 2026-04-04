package eu.inqudium.core.pipeline;

import java.util.UUID;

/**
 * Abstract base class for all wrapper layers in the pipeline.
 *
 * <p>{@code BaseWrapper} provides the core chain-execution mechanism: when a public
 * functional method (e.g. {@code run()}, {@code get()}, {@code call()}) is invoked
 * on the outermost wrapper, it calls {@link #initiateChain}, which generates a unique
 * call ID and begins a top-down traversal through every layer via
 * {@link #executeWithId}. Each layer executes its cross-cutting concern in
 * {@link #handleLayer} before forwarding the call to the next inner layer.</p>
 *
 * <h3>Execution Flow</h3>
 * <pre>{@code
 * outerWrapper.run()
 *   └── initiateChain(null)                      // generates callId
 *         └── outerWrapper.executeWithId(callId)  // handleLayer → forward
 *               └── innerWrapper.executeWithId(callId)  // handleLayer → forward
 *                     └── coreExecution            // calls delegate.run()
 * }</pre>
 *
 * <h3>Immutable Chain Structure</h3>
 * <p>The chain is linked in one direction only: each wrapper holds a reference to its
 * delegate (the next inner layer or the core target). There is no back-pointer to the
 * outer layer. This makes the chain structure immutable after construction and safe to
 * share across threads without synchronization. A single inner wrapper can even be
 * reused in multiple independent chains.</p>
 *
 * <h3>Core Execution</h3>
 * <p>Subclasses provide their terminal execution logic as an {@link InternalExecutor}
 * lambda passed to the constructor. This lambda is only invoked when the delegate is
 * not another wrapper (i.e. this is the innermost layer). The lambda captures the
 * delegate directly, eliminating the need for a separate {@code invokeCore()} method
 * and a {@code getDelegate()} accessor.</p>
 *
 * @param <T> the delegate type this wrapper wraps around
 * @param <A> the argument type flowing through the chain
 * @param <R> the return type flowing back through the chain
 * @param <S> the concrete self-type (recursive generic bound)
 */
public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
    implements Wrapper<S>, InternalExecutor<A, R> {

  /** The wrapped delegate — either another BaseWrapper (forming the chain) or the core target. */
  private final T delegate;

  /** Human-readable name for this layer, used in diagnostics and {@link #toStringHierarchy()}. */
  private final String name;

  /**
   * Identifies the wrapper chain this layer belongs to.
   * All layers wrapping the same core delegate share the same chain ID.
   * Generated once when the innermost wrapper (the one closest to the real delegate)
   * is constructed, and inherited by every outer wrapper added subsequently.
   */
  private final String chainId;

  /**
   * The next step in the execution chain, resolved once at construction time.
   * Points to the inner wrapper's {@code executeWithId} if the delegate is another
   * {@link BaseWrapper}, or to the subclass-provided core execution lambda if the
   * delegate is the terminal target. This eliminates the need for runtime
   * {@code instanceof} checks and abstract {@code invokeCore()} methods.
   */
  private final InternalExecutor<A, R> nextStep;

  /**
   * Constructs a new wrapper layer around the given delegate.
   *
   * <p>If the delegate is itself a {@code BaseWrapper}, this layer joins the same chain
   * by inheriting the delegate's {@link #chainId}, and the {@code coreExecution} parameter
   * is ignored (the chain forwards to the inner wrapper instead). Otherwise, a new chain ID
   * is generated and {@code coreExecution} becomes the terminal step in the chain.</p>
   *
   * @param name          a descriptive name for this layer (must not be {@code null})
   * @param delegate      the target to wrap (must not be {@code null})
   * @param coreExecution the terminal execution logic, invoked when the delegate is not a wrapper.
   *                      Typically a lambda like {@code (callId, arg) -> delegate.run()}
   * @throws IllegalArgumentException if {@code name} or {@code delegate} is {@code null}
   */
  @SuppressWarnings("unchecked")
  protected BaseWrapper(String name, T delegate, InternalExecutor<A, R> coreExecution) {
    if (name == null) {
      throw new IllegalArgumentException("Name must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    this.name = name;
    this.delegate = delegate;

    // Resolve chain structure once — the chain is immutable after construction
    if (delegate instanceof BaseWrapper<?,?,?,?> innerWrapper) {
      this.chainId = innerWrapper.getChainId();
      this.nextStep = (InternalExecutor<A, R>) delegate;
    } else {
      this.chainId = UUID.randomUUID().toString();
      this.nextStep = coreExecution;
    }
  }

  /**
   * Entry point for chain execution, called by the public functional methods
   * (e.g. {@code run()}, {@code get()}, {@code call()}).
   *
   * <p>Generates a fresh call ID via {@link #generateCallId()} and starts the
   * top-down traversal from this layer inward.</p>
   *
   * @param argument the argument to pass through the chain ({@code null} for Void types)
   * @return the result of the core delegate's execution
   */
  protected R initiateChain(A argument) {
    return this.executeWithId(generateCallId(), argument);
  }

  /**
   * Processes this layer and propagates the call to the next step in the chain.
   *
   * <p>First this layer's {@link #handleLayer} runs, then the pre-resolved
   * {@code nextStep} is invoked. No runtime type checks are needed.</p>
   *
   * @param callId   the unique identifier for this invocation
   * @param argument the argument flowing through the chain
   * @return the result of the innermost delegate's execution
   */
  @Override
  public R executeWithId(String callId, A argument) {
    handleLayer(callId, argument);
    return nextStep.executeWithId(callId, argument);
  }

  /**
   * Hook for layer-specific cross-cutting logic, called once per layer during
   * each chain invocation.
   *
   * <p>The default implementation is a no-op. Override this in a subclass to add
   * behavior such as logging, metrics collection, MDC context propagation,
   * security checks, or transaction management. The {@code callId} can be used
   * to correlate log entries across all layers of a single invocation.</p>
   *
   * @param callId   unique identifier for this particular invocation, shared across all layers
   * @param argument the argument passed through the chain ({@code null} for Void types)
   */
  protected void handleLayer(String callId, A argument) {
    // No-op by default — override in subclasses to add cross-cutting behavior
  }

  /**
   * Factory method for creating unique call identifiers.
   *
   * <p>The default implementation uses {@link UUID#randomUUID()}. Override this
   * to supply alternative ID strategies (e.g. sequential counters, shorter IDs,
   * or externally provided correlation IDs).</p>
   *
   * @return a new unique call ID string
   */
  protected String generateCallId() {
    return UUID.randomUUID().toString();
  }

  /** {@inheritDoc} */
  @Override public String getChainId() { return chainId; }

  /** {@inheritDoc} */
  @Override public String getLayerDescription() { return name; }

  /**
   * {@inheritDoc}
   *
   * <p>Returns the delegate cast to the self-type if it is a {@code BaseWrapper},
   * or {@code null} if the delegate is the terminal target.</p>
   */
  @SuppressWarnings("unchecked")
  @Override public S getInner() {
    return (delegate instanceof BaseWrapper) ? (S) delegate : null;
  }
}
