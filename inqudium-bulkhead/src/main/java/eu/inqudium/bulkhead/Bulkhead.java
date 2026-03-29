package eu.inqudium.bulkhead;

import eu.inqudium.bulkhead.internal.SemaphoreBulkhead;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.BulkheadConfig;


/**
 * Imperative bulkhead — limits concurrent calls via semaphore isolation.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var bh = Bulkhead.of("inventoryService", BulkheadConfig.builder()
 *     .maxConcurrentCalls(10)
 *     .build());
 *
 * var result = bh.executeSupplier(() -> inventoryService.check(sku));
 * }</pre>
 *
 * <p>The permit is held for the duration of the call and released in a
 * {@code finally} block — no permit leakage (ADR-020).
 *
 * @since 0.1.0
 */
public interface Bulkhead extends InqDecorator {

    static Bulkhead of(String name, BulkheadConfig config) {
        return new SemaphoreBulkhead(name, config);
    }

    static Bulkhead ofDefaults(String name) {
        return new SemaphoreBulkhead(name, BulkheadConfig.ofDefaults());
    }

    BulkheadConfig getConfig();

    /** Returns the current number of in-flight calls. */
    int getConcurrentCalls();

    /** Returns the number of available permits. */
    int getAvailablePermits();

    @Override
    default InqElementType getElementType() {
        return InqElementType.BULKHEAD;
    }
}
