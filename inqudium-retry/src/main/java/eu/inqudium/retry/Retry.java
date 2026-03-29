package eu.inqudium.retry;

import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.retry.internal.BlockingRetry;

/**
 * Imperative retry element — re-executes failed operations with configurable
 * backoff. Uses {@code LockSupport.parkNanos} for waiting (virtual-thread safe).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var retry = Retry.of("paymentService", RetryConfig.builder()
 *     .maxAttempts(3)
 *     .build());
 *
 * var result = retry.executeSupplier(() -> paymentService.charge(order));
 * }</pre>
 *
 * @since 0.1.0
 */
public interface Retry extends InqDecorator {

    static Retry of(String name, RetryConfig config) {
        return new BlockingRetry(name, config);
    }

    static Retry ofDefaults(String name) {
        return new BlockingRetry(name, RetryConfig.ofDefaults());
    }

    RetryConfig getConfig();

    @Override
    default InqElementType getElementType() {
        return InqElementType.RETRY;
    }
}
