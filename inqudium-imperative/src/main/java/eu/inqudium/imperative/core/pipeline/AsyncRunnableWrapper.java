package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.RunnableWrapper;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Async wrapper for fire-and-forget operations that complete asynchronously.
 *
 * <p>The async counterpart to {@link RunnableWrapper}. Since an async "runnable" must
 * return a {@link CompletionStage} to signal completion, the delegate and the wrapper
 * both implement {@code Supplier<CompletionStage<Void>>}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Supplier<CompletionStage<Void>> asyncSend = () -> messageBroker.sendAsync(msg);
 * AsyncRunnableWrapper wrapped = new AsyncRunnableWrapper(bulkhead, asyncSend);
 * CompletionStage<Void> stage = wrapped.get();  // acquire permit, start send, release on completion
 * }</pre>
 */
public class AsyncRunnableWrapper
    extends AsyncBaseWrapper<Supplier<CompletionStage<Void>>, Void, Void, AsyncRunnableWrapper>
    implements Supplier<CompletionStage<Void>> {

  private static InternalAsyncExecutor<Void, Void> coreFor(Supplier<CompletionStage<Void>> delegate) {
    return (chainId, callId, arg) -> delegate.get();
  }

  /** Creates a wrapper with an {@link InqAsyncDecorator} providing name and around-advice. */
  public AsyncRunnableWrapper(InqAsyncDecorator<Void, Void> decorator,
                               Supplier<CompletionStage<Void>> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /** Creates a wrapper with a custom {@link AsyncLayerAction}. */
  public AsyncRunnableWrapper(String name, Supplier<CompletionStage<Void>> delegate,
                               AsyncLayerAction<Void, Void> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /** Creates a wrapper with pass-through behavior. */
  public AsyncRunnableWrapper(String name, Supplier<CompletionStage<Void>> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  @Override
  public CompletionStage<Void> get() {
    return initiateChain(null);
  }
}
