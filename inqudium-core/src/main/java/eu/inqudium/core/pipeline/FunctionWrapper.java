package eu.inqudium.core.pipeline;

import java.util.function.Function;

/**
 * A homogeneous wrapper for the {@link Function} interface.
 *
 * @param <I> the input type of the function
 * @param <O> the output type of the function
 */
public class FunctionWrapper<I, O>
    extends BaseWrapper<Function<I, O>, I, O, FunctionWrapper<I, O>>
    implements Function<I, O> {

  /**
   * Creates a wrapper with a {@link InqDecorator} providing name and around-advice.
   */
  public FunctionWrapper(InqDecorator<I, O> decorator, Function<I, O> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /**
   * Creates a wrapper with a custom {@link LayerAction}.
   */
  public FunctionWrapper(String name, Function<I, O> delegate, LayerAction<I, O> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior.
   */
  public FunctionWrapper(String name, Function<I, O> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  private static <I, O> InternalExecutor<I, O> coreFor(Function<I, O> delegate) {
    return (chainId, callId, input) -> delegate.apply(input);
  }

  @Override
  public O apply(I input) {
    return initiateChain(input);
  }
}
