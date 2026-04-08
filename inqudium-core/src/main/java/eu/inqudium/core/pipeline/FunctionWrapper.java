package eu.inqudium.core.pipeline;

import java.util.function.Function;

/**
 * A homogeneous wrapper for the {@link Function} interface.
 *
 * <p>Unlike the void-argument wrappers ({@link RunnableWrapper},
 * {@link SupplierWrapper}), this wrapper passes the function's input argument
 * through the chain as the {@code A} type parameter. Each layer in the chain
 * can inspect or modify the argument before forwarding it.</p>
 *
 * @param <I> the input type of the function (flows through the chain as the argument)
 * @param <O> the output type of the function (flows back through the chain as the result)
 */
public class FunctionWrapper<I, O>
    extends BaseWrapper<Function<I, O>, I, O, FunctionWrapper<I, O>>
    implements Function<I, O> {

  /**
   * Creates a wrapper using a decorator's name and around-advice.
   *
   * @param decorator the decorator providing metadata and layer logic
   * @param delegate  the function to wrap
   */
  public FunctionWrapper(InqDecorator<I, O> decorator, Function<I, O> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /**
   * Creates a wrapper with an explicit name and layer action.
   *
   * @param name        human-readable layer name
   * @param delegate    the function to wrap
   * @param layerAction the around-advice for this layer
   */
  public FunctionWrapper(String name, Function<I, O> delegate, LayerAction<I, O> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /**
   * Creates a pass-through wrapper (structural only, no custom behavior).
   *
   * @param name     human-readable layer name
   * @param delegate the function to wrap
   */
  public FunctionWrapper(String name, Function<I, O> delegate) {
    this(name, delegate, LayerAction.passThrough());
  }

  /**
   * Builds the terminal core execution lambda for {@code Function<I, O>}.
   *
   * <p>Invokes {@code delegate.apply(input)} with the argument that has
   * been passed through the chain. This is the only wrapper type where
   * the chain argument is non-null and semantically meaningful.</p>
   *
   * @param delegate the real function to invoke at the end of the chain
   * @param <I>      the function's input type
   * @param <O>      the function's output type
   * @return a terminal {@link InternalExecutor} that calls {@code delegate.apply(input)}
   */
  private static <I, O> InternalExecutor<I, O> coreFor(Function<I, O> delegate) {
    return (chainId, callId, input) -> delegate.apply(input);
  }

  /**
   * Entry point: initiates chain traversal with the given input argument.
   *
   * @param input the function argument, passed through all layers to the delegate
   * @return the function's result, potentially modified by intermediate layers
   */
  @Override
  public O apply(I input) {
    return initiateChain(input);
  }
}
