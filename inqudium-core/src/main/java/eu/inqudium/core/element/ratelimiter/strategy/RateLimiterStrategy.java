package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.RateLimiterState;
import eu.inqudium.core.element.ratelimiter.ReservationResult;

import java.time.Duration;
import java.time.Instant;

/**
 * Strategy interface for rate limiting algorithms.
 *
 * <p><strong>Immutability contract:</strong> All implementations must treat the
 * state parameter as immutable and return a new state instance for every
 * mutating operation. The imperative wrapper relies on this for its CAS-based
 * thread safety model.
 *
 * @param <S> the strategy-specific state type
 */
public interface RateLimiterStrategy<S extends RateLimiterState> {

    /**
     * Creates the initial state for this strategy.
     */
    S initial(RateLimiterConfig<S> config, Instant now);

    /**
     * Attempts to acquire permits without waiting.
     *
     * @return a {@link RateLimitPermission} indicating whether permits were granted
     */
    RateLimitPermission<S> tryAcquirePermissions(S state, RateLimiterConfig<S> config, Instant now, int permits);

    /**
     * Reserves permits, possibly with a wait duration.
     * If the wait would exceed the timeout, returns a timed-out result without consuming permits.
     */
    ReservationResult<S> reservePermissions(S state, RateLimiterConfig<S> config, Instant now, int permits, Duration timeout);

    /**
     * Removes all available permits from the state.
     *
     * <p><strong>Semantic contract:</strong> Drain must NOT increment the epoch.
     * Existing reservations are honored — only the available (unreserved) permits
     * are removed. The imperative wrapper does not wake parked threads on drain.
     *
     * @return a new state with zero available permits and unchanged epoch
     */
    S drain(S state, RateLimiterConfig<S> config, Instant now);

    /**
     * Restores the state to full capacity and increments the epoch.
     *
     * <p><strong>Semantic contract:</strong> Reset MUST increment the epoch.
     * This invalidates all pending reservations — the imperative wrapper wakes
     * all parked threads so they can retry against the fresh state.
     *
     * @return a new state with full capacity and incremented epoch
     */
    S reset(S state, RateLimiterConfig<S> config, Instant now);

    /**
     * Returns permits to the bucket, capped at the configured capacity.
     */
    S refund(S state, RateLimiterConfig<S> config, int permits);

    /**
     * Returns the number of currently available permits after applying any pending refills.
     */
    int availablePermits(S state, RateLimiterConfig<S> config, Instant now);
}
