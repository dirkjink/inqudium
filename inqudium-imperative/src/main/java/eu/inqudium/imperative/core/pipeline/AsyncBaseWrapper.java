package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.BaseWrapper;
import eu.inqudium.core.pipeline.Wrapper;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for all async wrapper layers in the pipeline.
 *
 * <p>The async counterpart to {@link BaseWrapper}. Provides the same immutable chain
 * structure, zero-allocation ID generation, and pre-resolved {@code nextStep} — but
 * every execution returns a {@link CompletionStage} instead of a direct result.</p>
 *
 * <h3>Execution Flow</h3>
 * <pre>{@code
 * outerWrapper.get()
 *   └── initiateChain(null)
 *         └── outer.executeAsync(chainId, callId, null)
 *               └── outerAction.executeAsync(chainId, callId, null, next=inner)
 *                     │  acquire()                                // sync start phase
 *                     └── inner.executeAsync(chainId, callId, null)
 *                           └── innerAction.executeAsync(chainId, callId, null, next=core)
 *                                 └── coreExecution               // returns CompletionStage
 *                                 └── .whenComplete(...)          // async end phase (inner)
 *                     └── .whenComplete(...)                       // async end phase (outer)
 * }</pre>
 *
 * @param <T> the delegate type this wrapper wraps around
 * @param <A> the argument type flowing through the chain
 * @param <R> the result type carried by the CompletionStage
 * @param <S> the concrete self-type (recursive generic bound)
 */
public abstract class AsyncBaseWrapper<T, A, R, S extends AsyncBaseWrapper<T, A, R, S>>
    implements Wrapper<S>, InternalAsyncExecutor<A, R> {

  private static final AtomicLong CHAIN_ID_COUNTER = new AtomicLong();

  private final T delegate;
  private final String name;
  private final long chainId;
  private final InternalAsyncExecutor<A, R> nextStep;
  private final AsyncLayerAction<A, R> layerAction;
  private final AtomicLong callIdCounter;

  /**
   * Full constructor with a custom {@link AsyncLayerAction}.
   */
  @SuppressWarnings("unchecked")
  protected AsyncBaseWrapper(String name, T delegate,
                             InternalAsyncExecutor<A, R> coreExecution,
                             AsyncLayerAction<A, R> layerAction) {
    if (name == null) {
      throw new IllegalArgumentException("Name must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    this.name = name;
    this.delegate = delegate;
    this.layerAction = layerAction;

    if (delegate instanceof AsyncBaseWrapper<?, ?, ?, ?> innerWrapper) {
      this.chainId = innerWrapper.getChainId();
      this.callIdCounter = innerWrapper.callIdCounter;
      this.nextStep = (InternalAsyncExecutor<A, R>) delegate;
    } else {
      this.chainId = CHAIN_ID_COUNTER.incrementAndGet();
      this.callIdCounter = new AtomicLong();
      this.nextStep = coreExecution;
    }
  }

  /**
   * Constructor with pass-through behavior.
   */
  protected AsyncBaseWrapper(String name, T delegate,
                             InternalAsyncExecutor<A, R> coreExecution) {
    this(name, delegate, coreExecution, AsyncLayerAction.passThrough());
  }

  /**
   * Constructor using an {@link InqAsyncDecorator} for both name and around-advice.
   */
  protected AsyncBaseWrapper(InqAsyncDecorator<A, R> decorator, T delegate,
                             InternalAsyncExecutor<A, R> coreExecution) {
    this(decorator.getName(), delegate, coreExecution, decorator);
  }

  /**
   * Entry point for async chain execution. Generates a call ID and starts traversal.
   */
  protected CompletionStage<R> initiateChain(A argument) {
    return this.executeAsync(chainId, generateCallId(), argument);
  }

  /**
   * Delegates to this layer's {@link AsyncLayerAction}, passing the next step.
   */
  @Override
  public CompletionStage<R> executeAsync(long chainId, long callId, A argument) {
    return layerAction.executeAsync(chainId, callId, argument, nextStep);
  }

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
    return (delegate instanceof AsyncBaseWrapper) ? (S) delegate : null;
  }
}
