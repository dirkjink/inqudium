package eu.inqudium.core.pipeline;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for all wrapper layers in the pipeline.
 *
 * <p>{@code BaseWrapper} provides the core chain-execution mechanism. When a public
 * functional method is invoked on the outermost wrapper, it calls {@link #initiateChain},
 * which generates a unique call ID and begins a top-down traversal through every layer
 * via {@link #executeWithId}.</p>
 *
 * <h3>Zero-Allocation ID Generation</h3>
 * <p>Both chain IDs and call IDs use primitive {@code long} counters backed by
 * {@link AtomicLong}. This eliminates the overhead of {@code UUID.randomUUID().toString()}
 * (which involves {@code SecureRandom}, a 128-bit UUID object, and a 36-character String)
 * in favor of a single lock-free CAS operation with no object allocation.</p>
 *
 * <h3>Core Execution</h3>
 * <p>Subclasses provide their terminal execution logic as an {@link InternalExecutor}
 * lambda passed to the constructor. This lambda captures the delegate directly,
 * eliminating the need for a separate {@code invokeCore()} method.</p>
 *
 * @param <T> the delegate type this wrapper wraps around
 * @param <A> the argument type flowing through the chain
 * @param <R> the return type flowing back through the chain
 * @param <S> the concrete self-type (recursive generic bound)
 */
public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
    implements Wrapper<S>, InternalExecutor<A, R> {

  /** Global counter for chain IDs — unique per JVM, monotonically increasing. */
  private static final AtomicLong CHAIN_ID_COUNTER = new AtomicLong();

  private final T delegate;
  private final String name;
  private final long chainId;
  private final InternalExecutor<A, R> nextStep;

  /**
   * Per-instance counter for call IDs — avoids contention on a global static counter.
   * Only concurrent calls on the same wrapper instance compete for this counter.
   */
  private final AtomicLong callIdCounter = new AtomicLong();

  /**
   * Constructs a new wrapper layer around the given delegate.
   *
   * <p>If the delegate is itself a {@code BaseWrapper}, this layer joins the same chain
   * by inheriting the delegate's {@link #chainId}, and {@code coreExecution} is ignored.
   * Otherwise, a new chain ID is generated and {@code coreExecution} becomes the terminal
   * step in the chain.</p>
   *
   * @param name          a descriptive name for this layer (must not be {@code null})
   * @param delegate      the target to wrap (must not be {@code null})
   * @param coreExecution the terminal execution logic when the delegate is not a wrapper
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

    if (delegate instanceof BaseWrapper<?,?,?,?> innerWrapper) {
      this.chainId = innerWrapper.getChainId();
      this.nextStep = (InternalExecutor<A, R>) delegate;
    } else {
      this.chainId = CHAIN_ID_COUNTER.incrementAndGet();
      this.nextStep = coreExecution;
    }
  }

  /**
   * Entry point for chain execution. Generates a fresh call ID and starts traversal.
   */
  protected R initiateChain(A argument) {
    return this.executeWithId(generateCallId(), argument);
  }

  /**
   * Processes this layer and propagates the call to the next step.
   */
  @Override
  public R executeWithId(long callId, A argument) {
    handleLayer(callId, argument);
    return nextStep.executeWithId(callId, argument);
  }

  /**
   * Hook for layer-specific cross-cutting logic. No-op by default.
   *
   * @param callId   unique identifier for this invocation (primitive, zero-allocation)
   * @param argument the argument passed through the chain
   */
  protected void handleLayer(long callId, A argument) {
    // No-op by default — override in subclasses to add cross-cutting behavior
  }

  /**
   * Creates a unique call identifier. The default implementation uses a global
   * {@link AtomicLong} counter — a single CAS operation with no object allocation.
   *
   * <p>Override to supply external correlation IDs (e.g. from HTTP headers).</p>
   *
   * @return a unique call ID
   */
  protected long generateCallId() {
    return callIdCounter.incrementAndGet();
  }

  @Override public long getChainId() { return chainId; }
  @Override public String getLayerDescription() { return name; }

  @SuppressWarnings("unchecked")
  @Override public S getInner() {
    return (delegate instanceof BaseWrapper) ? (S) delegate : null;
  }
}
