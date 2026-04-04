package eu.inqudium.imperative.bulkhead;

import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InqProxyFactory;

/**
 * Factory that creates dynamic proxies protecting service methods with a bulkhead.
 *
 * <p>Routes method calls by return type:</p>
 * <ul>
 *   <li><b>Sync methods</b> → {@code LayerAction.execute} (acquire permit, execute, release in finally)</li>
 *   <li><b>Async methods</b> (returning {@link java.util.concurrent.CompletionStage}) →
 *       {@code AsyncLayerAction.executeAsync} (acquire sync, release on completion)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Works with any Bulkhead type parameterization
 * Bulkhead<Void, String> bulkhead = Bulkhead.of(config);
 * BulkheadProxyFactory factory = new BulkheadProxyFactory(bulkhead);
 *
 * PaymentService protected = factory.protect(PaymentService.class, realService);
 * protected.charge(order);                                // sync path
 * CompletionStage<Receipt> r = protected.chargeAsync(o);  // async path
 * }</pre>
 *
 * @since 0.4.0
 */
public class BulkheadProxyFactory implements InqProxyFactory {

  private final InqProxyFactory delegate;

  /**
   * Creates a factory that protects services with the given bulkhead.
   *
   * <p>Accepts any {@code Bulkhead<?, ?>}. If the bulkhead also implements
   * {@link InqAsyncDecorator}, async methods are automatically routed through
   * the two-phase async pipeline.</p>
   *
   * @param bulkhead the bulkhead to protect services with
   */
  public BulkheadProxyFactory(Bulkhead<?, ?> bulkhead) {
    if (bulkhead instanceof InqAsyncDecorator<?, ?> async) {
      this.delegate = InqProxyFactory.from(bulkhead, async);
    } else {
      this.delegate = InqProxyFactory.fromSync(bulkhead);
    }
  }

  @Override
  public <T> T protect(Class<T> serviceInterface, T target) {
    return delegate.protect(serviceInterface, target);
  }
}
