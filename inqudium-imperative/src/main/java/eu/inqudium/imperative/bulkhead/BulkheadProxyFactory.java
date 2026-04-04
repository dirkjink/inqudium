package eu.inqudium.imperative.bulkhead;

import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InqProxyFactory;

/**
 * Factory that creates dynamic proxies protecting service methods with a bulkhead.
 *
 * <p>The returned proxies implement the {@link eu.inqudium.core.pipeline.Wrapper} interface,
 * enabling chain visualization via {@code toStringHierarchy()}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Bulkhead<Void, String> bulkhead = Bulkhead.of(config);
 * BulkheadProxyFactory factory = new BulkheadProxyFactory(bulkhead);
 *
 * PaymentService proxy = factory.protect(PaymentService.class, realService);
 * proxy.charge(order);
 *
 * // Chain visualization
 * Wrapper<?> chain = (Wrapper<?>) proxy;
 * System.out.println(chain.toStringHierarchy());
 * }</pre>
 *
 * @since 0.4.0
 */
public class BulkheadProxyFactory implements InqProxyFactory {

  private final InqProxyFactory delegate;

  /**
   * Creates a factory that protects services with the given bulkhead.
   *
   * @param bulkhead the bulkhead to protect services with
   */
  public BulkheadProxyFactory(Bulkhead<?, ?> bulkhead) {
    if (bulkhead instanceof InqAsyncDecorator<?, ?> async) {
      this.delegate = InqProxyFactory.from(bulkhead.getName(), bulkhead, async);
    } else {
      this.delegate = InqProxyFactory.fromSync(bulkhead.getName(), bulkhead);
    }
  }

  @Override
  public <T> T protect(Class<T> serviceInterface, T target) {
    return delegate.protect(serviceInterface, target);
  }
}
