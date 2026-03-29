package eu.inqudium.ratelimiter;

import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.ratelimiter.internal.TokenBucketRateLimiter;

import java.util.function.Supplier;

/**
 * Imperative rate limiter — controls throughput via a token bucket algorithm.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
 *     .limitForPeriod(100)
 *     .limitRefreshPeriod(Duration.ofSeconds(1))
 *     .build());
 *
 * var result = rl.executeSupplier(() -> apiClient.call());
 * }</pre>
 *
 * @since 0.1.0
 */
public interface RateLimiter extends InqDecorator {

    static RateLimiter of(String name, RateLimiterConfig config) {
        return new TokenBucketRateLimiter(name, config);
    }

    static RateLimiter ofDefaults(String name) {
        return new TokenBucketRateLimiter(name, RateLimiterConfig.ofDefaults());
    }

    RateLimiterConfig getConfig();

    /**
     * Acquires a permit. Blocks up to {@code timeoutDuration} if configured.
     * Throws {@link eu.inqudium.core.ratelimiter.InqRequestNotPermittedException} if denied.
     */
    void acquirePermit();

    <T> Supplier<T> decorateSupplier(Supplier<T> supplier);


    Runnable decorateRunnable(Runnable runnable);

    default <T> T executeSupplier(Supplier<T> supplier) {
        return decorateSupplier(supplier).get();
    }

    default void executeRunnable(Runnable runnable) {
        decorateRunnable(runnable).run();
    }

    @Override
    default InqElementType getElementType() {
        return InqElementType.RATE_LIMITER;
    }
}
