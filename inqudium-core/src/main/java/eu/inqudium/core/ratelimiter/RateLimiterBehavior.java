package eu.inqudium.core.ratelimiter;

import java.time.Duration;
import java.time.Instant;

/**
 * Behavioral contract for rate limiter permit acquisition.
 *
 * <p>Pure function — returns immediately with a result. Never blocks.
 * The paradigm module decides how to wait if denied (ADR-019).
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface RateLimiterBehavior {

    /**
     * Attempts to acquire a permit from the token bucket.
     *
     * @param state  current token bucket state
     * @param config rate limiter configuration
     * @return result with permit status, wait estimate, and updated state
     */
    PermitResult tryAcquire(TokenBucketState state, RateLimiterConfig config);

    /**
     * Returns the default token bucket behavior.
     *
     * @return the default behavior
     */
    static RateLimiterBehavior defaultBehavior() {
        return DefaultRateLimiterBehavior.INSTANCE;
    }
}

/**
 * Default token bucket implementation.
 */
final class DefaultRateLimiterBehavior implements RateLimiterBehavior {

    static final DefaultRateLimiterBehavior INSTANCE = new DefaultRateLimiterBehavior();

    private DefaultRateLimiterBehavior() {}

    @Override
    public PermitResult tryAcquire(TokenBucketState state, RateLimiterConfig config) {
        Instant now = config.getClock().instant();

        // Refill tokens based on elapsed time
        Duration elapsed = Duration.between(state.lastRefillTimestamp(), now);
        long periodNanos = config.getLimitRefreshPeriod().toNanos();
        long periodsElapsed = periodNanos > 0 ? elapsed.toNanos() / periodNanos : 0;

        int refilled = state.availableTokens() + (int) (periodsElapsed * config.getLimitForPeriod());
        int capped = Math.min(refilled, config.getBucketSize());

        Instant lastRefill = periodsElapsed > 0
                ? state.lastRefillTimestamp().plus(config.getLimitRefreshPeriod().multipliedBy(periodsElapsed))
                : state.lastRefillTimestamp();

        // Try to acquire a token
        if (capped > 0) {
            var newState = new TokenBucketState(capped - 1, lastRefill);
            return PermitResult.permitted(newState);
        }

        // Denied — estimate wait until next token
        Duration untilNextRefill = config.getLimitRefreshPeriod()
                .minus(Duration.between(lastRefill, now));
        if (untilNextRefill.isNegative() || untilNextRefill.isZero()) {
            untilNextRefill = config.getLimitRefreshPeriod();
        }

        var unchangedState = new TokenBucketState(0, lastRefill);
        return PermitResult.denied(untilNextRefill, unchangedState);
    }
}
