package eu.inqudium.core.pipeline;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for all wrapper layers in the pipeline.
 *
 * <p>{@code BaseWrapper} provides the core chain-execution mechanism using
 * <strong>around-semantics</strong>: each layer's behavior is defined by a
 * {@link LayerAction} that wraps the next step in the chain, similar to a
 * Servlet Filter or Spring AOP {@code @Around} advice.</p>
 *
 * <h3>Execution Flow</h3>
 * <pre>{@code
 * outerWrapper.run()
 *   └── initiateChain(null)
 *         └── outer.execute(chainId, callId, null)
 *               └── outerAction.execute(chainId, callId, null, next=inner)
 *                     // ... pre-processing ...
 *                     └── inner.execute(chainId, callId, null)
 *                           └── innerAction.execute(chainId, callId, null, next=core)
 *                                 // ... pre-processing ...
 *                                 └── coreExecution   // delegate.run()
 *                                 // ... post-processing ...
 *                           // ... post-processing ...
 * }</pre>
 *
 * <h3>Layer Composition</h3>
 * <p>Layers are composed by wrapping one wrapper around another. Each wrapper holds
 * a {@link LayerAction} that defines its cross-cutting concern, and a reference to
 * the next step (either the inner wrapper or the core execution). The chain is
 * immutable after construction.</p>
 *
 * <h3>Zero-Allocation Tracing</h3>
 * <p>Both the chain ID and the call ID flow through the chain as primitive {@code long}
 * values. Chain IDs use a global counter, call IDs use a per-chain counter — no object
 * allocation on the hot path.</p>
 *
 * @param <T> the delegate type this wrapper wraps around
 * @param <A> the argument type flowing through the chain
 * @param <R> the return type flowing back through the chain
 * @param <S> the concrete self-type (recursive generic bound)
 */
public abstract class BaseWrapper<T, A, R, S extends BaseWrapper<T, A, R, S>>
    implements Wrapper<S>, InternalExecutor<A, R> {

  /**
   * Global counter for chain IDs — unique per JVM, monotonically increasing.
   */
  private static final AtomicLong CHAIN_ID_COUNTER = new AtomicLong();

  private final T delegate;
  private final String name;
  private final long chainId;
  private final InternalExecutor<A, R> nextStep;
  private final LayerAction<A, R> layerAction;

  /**
   * Shared call ID counter for this chain. Created once by the innermost wrapper
   * and inherited by every outer wrapper, just like the {@link #chainId}.
   */
  private final AtomicLong callIdCounter;

  /**
   * Constructs a new wrapper layer with a custom {@link LayerAction}.
   *
   * <p>The {@code layerAction} defines this layer's around-advice. It receives the
   * chain ID, call ID, argument, and a reference to the next step. The action decides
   * when and whether to invoke the next step, enabling pre-processing, post-processing,
   * exception handling, caching, and conditional execution.</p>
   *
   * @param name          a descriptive name for this layer (must not be {@code null})
   * @param delegate      the target to wrap (must not be {@code null})
   * @param coreExecution the terminal execution logic (used only when delegate is not a wrapper)
   * @param layerAction   the around-advice for this layer
   */
  @SuppressWarnings("unchecked")
  protected BaseWrapper(String name, T delegate, InternalExecutor<A, R> coreExecution,
                        LayerAction<A, R> layerAction) {
    if (name == null) {
      throw new IllegalArgumentException("Name must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    this.name = name;
    this.delegate = delegate;
    this.layerAction = layerAction;

    if (delegate instanceof BaseWrapper<?, ?, ?, ?> innerWrapper) {
      this.chainId = innerWrapper.getChainId();
      this.callIdCounter = innerWrapper.callIdCounter;
      this.nextStep = (InternalExecutor<A, R>) delegate;
    } else {
      this.chainId = CHAIN_ID_COUNTER.incrementAndGet();
      this.callIdCounter = new AtomicLong();
      this.nextStep = coreExecution;
    }
  }

  /**
   * Constructs a new wrapper layer with pass-through behavior (no around-advice).
   *
   * <p>Equivalent to calling the full constructor with {@link LayerAction#passThrough()}.</p>
   *
   * @param name          a descriptive name for this layer
   * @param delegate      the target to wrap
   * @param coreExecution the terminal execution logic
   */
  protected BaseWrapper(String name, T delegate, InternalExecutor<A, R> coreExecution) {
    this(name, delegate, coreExecution, LayerAction.passThrough());
  }

  /**
   * Constructs a new wrapper layer using a {@link InqDecorator} for both name and around-advice.
   *
   * <p>The decorator provides its name via {@link InqDecorator#getName()} and its
   * around-advice via its {@link LayerAction#execute} implementation. This is the
   * preferred constructor when plugging reusable resilience elements (bulkhead,
   * circuit breaker, retry, etc.) into the chain.</p>
   *
   * @param decorator     the decorator providing name and around-advice
   * @param delegate      the target to wrap
   * @param coreExecution the terminal execution logic
   */
  protected BaseWrapper(InqDecorator<A, R> decorator, T delegate, InternalExecutor<A, R> coreExecution) {
    this(decorator.getName(), delegate, coreExecution, decorator);
  }

  /**
   * Entry point for chain execution. Generates a fresh call ID and starts traversal,
   * passing both the chain ID and the call ID through every layer.
   */
  protected R initiateChain(A argument) {
    return this.execute(chainId, generateCallId(), argument);
  }

  /**
   * Delegates to this layer's {@link LayerAction}, passing the next step as a callback.
   *
   * <p>The layer action has full control over the execution: it can inspect or modify
   * the argument, measure timing, catch exceptions, skip the next step entirely, or
   * call it multiple times (e.g. for retry logic).</p>
   *
   * @param chainId  the chain identifier, shared across all layers
   * @param callId   the call identifier, unique per invocation
   * @param argument the argument flowing through the chain
   * @return the result of the chain execution
   */
  @Override
  public R execute(long chainId, long callId, A argument) {
    return layerAction.execute(chainId, callId, argument, nextStep);
  }

  /**
   * Creates a unique call identifier using the chain's shared counter.
   * Override to supply external correlation IDs.
   */
  protected long generateCallId() {
    return callIdCounter.incrementAndGet();
  }

  @Override
  public long getChainId() {
    return chainId;
  }

  @Override
  public String getLayerDescription() {
    return name;
  }

  @SuppressWarnings("unchecked")
  @Override
  public S getInner() {
    return (delegate instanceof BaseWrapper) ? (S) delegate : null;
  }
}
