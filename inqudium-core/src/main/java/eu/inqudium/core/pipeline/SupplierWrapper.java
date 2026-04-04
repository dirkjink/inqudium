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

  public SupplierWrapper(String name, Supplier<T> delegate) {
    super(name, delegate, (callId, arg) -> delegate.get());
  }

  @Override
  public T get() {
    return initiateChain(null);
  }
}
