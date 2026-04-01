package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.bulkhead.BulkheadConfig;
import eu.inqudium.core.element.bulkhead.config.InqBulkheadConfig;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.imperative.bulkhead.strategy.SemaphoreBulkheadStrategy;

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
 * <p>The strategy is selected automatically by {@link BulkheadConfig.Builder#build()}.
 * All auto-selected strategies are {@link BlockingBulkheadStrategy}
 * instances. For custom non-blocking strategies, use the reactive bulkhead facade.
 *
 * @since 0.1.0
 */
public interface Bulkhead extends InqDecorator {

  static Bulkhead of(InqConfig config) {
    return of(config.of(InqBulkheadConfig.class).orElseThrow());
  }

  /**
   * Creates a bulkhead with the given configuration.
   *
   * <p>Uses {@link BulkheadConfig#getBlockingStrategy()} — throws if a
   * non-blocking strategy was configured (imperative paradigm requires blocking).
   */
  static Bulkhead of(InqBulkheadConfig config) {
    return new ImperativeBulkhead(config, new SemaphoreBulkheadStrategy(config.maxConcurrentCalls()));
  }

  InqBulkheadConfig getConfig();

  int getConcurrentCalls();

  int getAvailablePermits();

  @Override
  default InqElementType getElementType() {
    return InqElementType.BULKHEAD;
  }
}
