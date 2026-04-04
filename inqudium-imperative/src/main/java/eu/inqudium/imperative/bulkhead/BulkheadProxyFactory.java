package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.pipeline.InqProxyFactory;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InqAsyncProxyFactory;

/**
 * Factory that creates dynamic proxies protecting service methods with a bulkhead.
 *
 * <p>If the bulkhead implements {@link InqAsyncDecorator}, an {@link InqAsyncProxyFactory}
 * is used — routing async methods through the two-phase async pipeline. Otherwise,
 * a sync-only {@link InqProxyFactory} is used.</p>
 *
 * @since 0.4.0
 */
public class BulkheadProxyFactory implements InqProxyFactory {

  private final InqProxyFactory delegate;

  public BulkheadProxyFactory(Bulkhead<?, ?> bulkhead) {
    if (bulkhead instanceof InqAsyncDecorator<?, ?> async) {
      this.delegate = InqAsyncProxyFactory.of(bulkhead.getName(), bulkhead, async);
    } else {
      this.delegate = InqProxyFactory.of(bulkhead.getName(), bulkhead);
    }
  }

  @Override
  public <T> T protect(Class<T> serviceInterface, T target) {
    return delegate.protect(serviceInterface, target);
  }
}
