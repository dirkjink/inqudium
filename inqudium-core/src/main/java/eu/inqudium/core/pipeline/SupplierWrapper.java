package eu.inqudium.core.pipeline;

import java.util.function.Supplier;

/**
 * A homogeneous wrapper for the {@link Supplier} interface.
 *
 * <h3>Usage with Decorator</h3>
 * <pre>{@code
 * BulkheadDecorator<Void, String> bulkhead = new BulkheadDecorator<>("pool", 10);
 * SupplierWrapper<String> protected = new SupplierWrapper<>(bulkhead, () -> callApi());
 * }</pre>
 *
 * @param <T> the return type of the supplier
 */
public class SupplierWrapper<T>
    extends BaseWrapper<Supplier<T>, Void, T, SupplierWrapper<T>>
    implements Supplier<T> {

  /**
   * Creates a wrapper with a {@link InqDecorator} providing name and around-advice.
   */
  public SupplierWrapper(InqDecorator<Void, T> decorator, Supplier<T> delegate) {
    super(decorator, delegate, coreFor(delegate));
  }

  /**
   * Creates a wrapper with a custom {@link LayerAction}.
   */
  public SupplierWrapper(String name, Supplier<T> delegate, LayerAction<Void, T> layerAction) {
    super(name, delegate, coreFor(delegate), layerAction);
  }

  /**
   * Creates a wrapper with pass-through behavior.
   */
  public SupplierWrapper(String name, Supplier<T> delegate) {
    super(name, delegate, coreFor(delegate));
  }

  private static <T> InternalExecutor<Void, T> coreFor(Supplier<T> delegate) {
    return (chainId, callId, arg) -> delegate.get();
  }

  @Override
  public T get() {
    return initiateChain(null);
  }
}
