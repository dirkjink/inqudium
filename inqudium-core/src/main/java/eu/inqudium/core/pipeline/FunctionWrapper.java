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

  public FunctionWrapper(String name, Function<I, O> delegate) {
    super(name, delegate, (callId, input) -> delegate.apply(input));
  }

  @Override
  public O apply(I input) {
    return initiateChain(input);
  }
}
