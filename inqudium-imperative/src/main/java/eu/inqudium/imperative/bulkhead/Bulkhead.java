package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.strategy.BulkheadStrategy;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.imperative.bulkhead.imperative.ImperativeBulkhead;

/**
 * Imperative bulkhead — limits concurrent calls via pluggable strategies.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Static (Semaphore — default)
 * var bh = Bulkhead.of("inventoryService", BulkheadConfig.builder()
 *     .maxConcurrentCalls(10)
 *     .build());
 *
 * // Adaptive (AIMD)
 * var bh = Bulkhead.of("paymentService", BulkheadConfig.builder()
 *     .maxConcurrentCalls(25)
 *     .limitAlgorithm(new AimdLimitAlgorithm(25, 5, 100, 0.5))
 *     .build());
 *
 * // CoDel
 * var bh = Bulkhead.of("searchService", BulkheadConfig.builder()
 *     .maxConcurrentCalls(25)
 *     .codel(Duration.ofMillis(20), Duration.ofMillis(500))
 *     .build());
 *
 * var result = bh.executeSupplier(() -> inventoryService.check(sku));
 * }</pre>
 *
 * <p>The permit is held for the duration of the call and released in a
 * {@code finally} block — no permit leakage.
 *
 * <p>The strategy is selected automatically by {@link BulkheadConfig.Builder#build()}
 * based on the configured options, or set explicitly via
 * {@link BulkheadConfig.Builder#strategy}.
 *
 * @since 0.1.0
 */
public interface Bulkhead extends InqDecorator {

  /**
   * Creates a bulkhead with the given configuration.
   *
   * <p>The {@link BulkheadStrategy} is obtained from
   * {@link BulkheadConfig#getStrategy()}, which was resolved during
   * {@link BulkheadConfig.Builder#build()}.
   */
  static Bulkhead of(String name, BulkheadConfig config) {
    return new ImperativeBulkhead(name, config, config.getStrategy());
  }

  static Bulkhead ofDefaults(String name) {
    return of(name, BulkheadConfig.ofDefaults());
  }

  BulkheadConfig getConfig();

  int getConcurrentCalls();

  int getAvailablePermits();

  @Override
  default InqElementType getElementType() {
    return InqElementType.BULKHEAD;
  }
}
