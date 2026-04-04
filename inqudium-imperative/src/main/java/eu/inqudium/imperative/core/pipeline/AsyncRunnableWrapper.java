package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.RunnableWrapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Async wrapper for fire-and-forget operations.
 *
 * <p>The async counterpart to {@link RunnableWrapper}. Accepts a plain {@link Runnable}
 * as the delegate — the async lifecycle comes from the surrounding {@link AsyncLayerAction}
 * layers, not from the delegate. The core executes the Runnable synchronously and wraps
 * the result in an already-completed {@link CompletionStage}.</p>
 *
 * <p>Implements both {@link Supplier}{@code <CompletionStage<Void>>} (primary entry point)
 * and {@link Runnable} (enables homogeneous chaining with other {@code AsyncRunnableWrapper}
 * instances, following the same pattern as {@link RunnableWrapper}).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * AsyncRunnableWrapper wrapped = new AsyncRunnableWrapper(bulkhead, () -> sendEmail());
 * CompletionStage<Void> stage = wrapped.get();  // acquire → run → release on completion
 * }</pre>
 */
public class AsyncRunnableWrapper
    extends AsyncBaseWrapper<Runnable, Void, Void, AsyncRunnableWrapper>
    implements Supplier<CompletionStage<Void>>, Runnable {

  private static InternalAsyncExecutor<Void, Void> coreFor(Runnable delegate) {
    return (chainId, callId, arg) -> {
      delegate.run();
      return CompletableFuture.completedFuture(null);
    };
  }

  /** Creates a wrapper with an {@link InqAsyncDecorator} providing name and around-advice. */
  public AsyncRunnableWrapper(InqAsyncDecorator<Void, Void> decorator, Runnable delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /** Creates a wrapper with a custom {@link AsyncLayerAction}. */
  public AsyncRunnableWrapper(String name, Runnable delegate,
                               AsyncLayerAction<Void, Void> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /** Creates a wrapper with pass-through behavior. */
  public AsyncRunnableWrapper(String name, Runnable delegate) {
    super(name, delegate, coreFor(delegate));
  }

  /** Primary entry point — returns a {@link CompletionStage} for async lifecycle tracking. */
  @Override
  public CompletionStage<Void> get() {
    return initiateChain(null);
  }

  /**
   * Fire-and-forget entry point. Executes the async chain and discards the stage.
   * Primarily exists to enable homogeneous chaining (another {@code AsyncRunnableWrapper}
   * can wrap this one, since {@code T = Runnable} and this wrapper IS-A {@code Runnable}).
   */
  @Override
  public void run() {
    initiateChain(null);
  }
}
