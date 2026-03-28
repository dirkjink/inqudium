/**
 * Rate limiter contracts, configuration, and token bucket algorithm.
 *
 * <p>Controls throughput by limiting the number of calls within a time period.
 * Uses a token bucket algorithm that provides smooth rate limiting with configurable
 * burst tolerance — avoiding the boundary problem of fixed-window counters (ADR-019).
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code RateLimiterConfig} — immutable configuration: permits per period
 *       ({@code limitForPeriod}), refresh period ({@code limitRefreshPeriod}),
 *       burst capacity ({@code bucketSize}), and timeout for waiting callers.</li>
 *   <li>{@code RateLimiterBehavior} — contract: {@code tryAcquire(state, config) → PermitResult}.
 *       Pure function — refills tokens based on elapsed time via {@link eu.inqudium.core.InqClock},
 *       then checks availability.</li>
 *   <li>{@code TokenBucketState} — record: available tokens and last refill timestamp.</li>
 *   <li>{@code PermitResult} — record: permitted (boolean), wait duration estimate if denied,
 *       updated state.</li>
 * </ul>
 *
 * <p>The behavior returns immediately with a result — it never blocks. If denied,
 * the paradigm module decides how to wait ({@code parkNanos}, {@code delay()},
 * {@code Mono.delay()}) based on the {@code waitDuration} in the result.
 *
 * @see eu.inqudium.core.InqClock
 * @see eu.inqudium.core.exception.InqRequestNotPermittedException
 */
package eu.inqudium.core.ratelimiter;
