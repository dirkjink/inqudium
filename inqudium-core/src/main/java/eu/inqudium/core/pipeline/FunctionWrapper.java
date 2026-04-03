package eu.inqudium.core.pipeline;

import java.util.function.Function;

/**
 * A homogeneous wrapper for the Function interface.
 * * @param <I> The input type
 * @param <O> The output type
 */
public class FunctionWrapper<I, O>
    extends BaseWrapper<Function<I, O>, I, O, FunctionWrapper<I, O>>
    implements Function<I, O> {

  public FunctionWrapper(String name, Function<I, O> delegate) {
    super(name, delegate);
  }

  @Override
  public O apply(I input) {
    // Starts the chain and passes the input dynamically
    return initiateChain(input);
  }

  @Override
  protected O invokeCore(I input) {
    // Core execution receives the passed input
    return getDelegate().apply(input);
  }

  @Override
  protected void handleLayer(String callId, I input) {
    // E.g., logging the input along with the unique callId
  }
}
