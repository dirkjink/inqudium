package eu.inqudium.core.pipeline;

import java.util.function.Supplier;

/**
 * A homogeneous wrapper for the {@link Supplier} interface.
 *
 * <p>Since a {@code Supplier} takes no arguments, the argument type is {@code Void}
 * and the chain is initiated with {@code null}. The return value of the core supplier
 * propagates back through the chain to the caller.</p>
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
