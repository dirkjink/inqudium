package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.FunctionWrapper;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Async wrapper for functions that return a {@link CompletionStage}.
 *
 * <p>The async counterpart to {@link FunctionWrapper}. The input flows through
 * every layer's around-advice before reaching the core delegate.</p>
 *
 * @param <I> the input type of the function
 * @param <O> the result type carried by the CompletionStage
 */
public class AsyncFunctionWrapper<I, O>
    extends AsyncBaseWrapper<Function<I, CompletionStage<O>>, I, O, AsyncFunctionWrapper<I, O>>
    implements Function<I, CompletionStage<O>> {

  private static <I, O> InternalAsyncExecutor<I, O> coreFor(Function<I, CompletionStage<O>> delegate) {
    return (chainId, callId, input) -> delegate.apply(input);
  }

  /** Creates a wrapper with an {@link InqAsyncDecorator} providing name and around-advice. */
  public AsyncFunctionWrapper(InqAsyncDecorator<I, O> decorator,
                               Function<I, CompletionStage<O>> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /** Creates a wrapper with a custom {@link AsyncLayerAction}. */
  public AsyncFunctionWrapper(String name, Function<I, CompletionStage<O>> delegate,
                               AsyncLayerAction<I, O> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /** Creates a wrapper with pass-through behavior. */
  public AsyncFunctionWrapper(String name, Function<I, CompletionStage<O>> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  @Override
  public CompletionStage<O> apply(I input) {
    return initiateChain(input);
  }
}
