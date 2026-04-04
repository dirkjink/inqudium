package eu.inqudium.imperative.bulkhead;

import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InqProxyFactory;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;

/**
 * Factory that creates dynamic proxies protecting service methods with a bulkhead.
 *
 * <p>Routes method calls by return type:</p>
 * <ul>
 *   <li><b>Sync methods</b> → {@link LayerAction#execute} (acquire permit, execute, release in finally)</li>
 *   <li><b>Async methods</b> (returning {@link java.util.concurrent.CompletionStage}) →
 *       {@link AsyncLayerAction#executeAsync} (acquire sync, release on completion)</li>
 * </ul>
 *
 * <p>No wrapper objects are created per invocation — only a single
 * {@link eu.inqudium.core.pipeline.InternalExecutor} lambda.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Create a bulkhead
 * Bulkhead<Void, Object> bulkhead = Bulkhead.of(config);
 *
 * // Create a factory from the bulkhead
 * BulkheadProxyFactory factory = new BulkheadProxyFactory(bulkhead);
 *
 * // Protect any service interface
 * PaymentService protected = factory.protect(PaymentService.class, realService);
 *
 * // Sync method → bulkhead acquire → charge() → bulkhead release
 * protected.charge(order);
 *
 * // Async method → bulkhead acquire → chargeAsync() → release on completion
 * CompletionStage<Receipt> receipt = protected.chargeAsync(order);
 * }</pre>
 *
 * <h3>Sharing across services</h3>
 * <pre>{@code
 * // Same bulkhead, same permit pool, multiple services
 * BulkheadProxyFactory factory = new BulkheadProxyFactory(bulkhead);
 * InventoryService inventory = factory.protect(InventoryService.class, realInventory);
 * PricingService pricing = factory.protect(PricingService.class, realPricing);
 * // Both services share the same concurrency limit
 * }</pre>
 *
 * @since 0.4.0
 */
public class BulkheadProxyFactory implements InqProxyFactory {

  private final InqProxyFactory delegate;

  /**
   * Creates a factory that protects services with the given bulkhead.
   *
   * <p>If the bulkhead also implements {@link InqAsyncDecorator}, async methods
   * (returning {@link java.util.concurrent.CompletionStage}) are automatically routed
   * through the async two-phase pipeline. Otherwise, all methods go through the sync path.</p>
   *
   * @param bulkhead the bulkhead to protect services with
   */
  @SuppressWarnings("unchecked")
  public BulkheadProxyFactory(Bulkhead<Void, Object> bulkhead) {
    if (bulkhead instanceof InqAsyncDecorator<?, ?> async) {
      this.delegate = InqProxyFactory.from(bulkhead, (AsyncLayerAction<Void, Object>) async);
    } else {
      this.delegate = InqProxyFactory.fromSync(bulkhead);
    }
  }

  @Override
  public <T> T protect(Class<T> serviceInterface, T target) {
    return delegate.protect(serviceInterface, target);
  }
}
