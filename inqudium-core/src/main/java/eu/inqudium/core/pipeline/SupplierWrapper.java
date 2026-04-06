package eu.inqudium.core.pipeline;

import java.util.function.Supplier;

/**
 * A homogeneous wrapper for the {@link Supplier} interface.
 *
 * @param <T> the return type of the supplier
 */
public class SupplierWrapper<T>
    extends BaseWrapper<Supplier<T>, Void, T, SupplierWrapper<T>>
    implements Supplier<T> {

  public SupplierWrapper(InqDecorator<Void, T> decorator, Supplier<T> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  public SupplierWrapper(String name, Supplier<T> delegate, LayerAction<Void, T> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  public SupplierWrapper(String name, Supplier<T> delegate) {
    this(name, delegate, LayerAction.passThrough());
  }

  private static <T> InternalExecutor<Void, T> coreFor(Supplier<T> delegate) {
    return (chainId, callId, arg) -> delegate.get();
  }

  @Override
  public T get() {
    return initiateChain(null);
  }
}
