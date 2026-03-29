package eu.inqudium.timelimiter;

import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;
import eu.inqudium.timelimiter.internal.FutureTimeLimiter;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Imperative time limiter — bounds the caller's wait time without interrupting
 * the downstream operation.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var tl = TimeLimiter.of("paymentService", TimeLimiterConfig.builder()
 *     .timeoutDuration(Duration.ofSeconds(3))
 *     .build());
 *
 * // With CompletionStage (preferred)
 * CompletionStage<Payment> future = paymentService.chargeAsync(order);
 * Payment result = tl.executeFutureSupplier(() -> future);
 *
 * // With synchronous Supplier (runs on virtual thread internally)
 * Payment result = tl.executeSupplier(() -> paymentService.charge(order));
 * }</pre>
 *
 * <p>If the timeout fires, the caller receives {@code InqTimeLimitExceededException}.
 * The orphaned operation continues — observable via the configured
 * {@code onOrphanedResult}/{@code onOrphanedError} handlers (ADR-010).
 *
 * @since 0.1.0
 */
public interface TimeLimiter extends InqDecorator {

    static TimeLimiter of(String name, TimeLimiterConfig config) {
        return new FutureTimeLimiter(name, config);
    }

    static TimeLimiter ofDefaults(String name) {
        return new FutureTimeLimiter(name, TimeLimiterConfig.ofDefaults());
    }

    TimeLimiterConfig getConfig();

    /**
     * Decorates a future supplier with a timeout.
     *
     * @param futureSupplier a supplier that returns a CompletionStage
     * @param <T>            the result type
     * @return a supplier that applies the timeout to the future
     */
    <T> Supplier<T> decorateFutureSupplier(Supplier<CompletionStage<T>> futureSupplier);

    /**
     * Executes a future supplier with timeout protection.
     *
     * @param futureSupplier a supplier that returns a CompletionStage
     * @param <T>            the result type
     * @return the result
     */
    default <T> T executeFutureSupplier(Supplier<CompletionStage<T>> futureSupplier) {
        return decorateFutureSupplier(futureSupplier).get();
    }

    @Override
    default InqElementType getElementType() {
        return InqElementType.TIME_LIMITER;
    }
}
