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

  public FunctionWrapper(InqDecorator<I, O> decorator, Function<I, O> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  public FunctionWrapper(String name, Function<I, O> delegate, LayerAction<I, O> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  public FunctionWrapper(String name, Function<I, O> delegate) {
    this(name, delegate, LayerAction.passThrough());
  }

  private static <I, O> InternalExecutor<I, O> coreFor(Function<I, O> delegate) {
    return (chainId, callId, input) -> delegate.apply(input);
  }

  @Override
  public O apply(I input) {
    return initiateChain(input);
  }
}
