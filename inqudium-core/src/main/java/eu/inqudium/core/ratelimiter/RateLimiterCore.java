package eu.inqudium.core.ratelimiter;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure functional core of the token-bucket rate limiter.
 *
 * <p>All methods are static and side-effect-free. They accept the current
 * immutable {@link RateLimiterSnapshot} and return a new snapshot reflecting
 * the state change. No synchronization, no I/O, no mutation.
 *
 * <p>This design allows the same core logic to be shared between an
 * imperative (virtual-thread) wrapper and a reactive (Project Reactor) wrapper,
 * each providing its own concurrency and scheduling strategy.
 *
 * <h2>Token Bucket Algorithm</h2>
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │  Bucket  [■ ■ ■ ■ □ □ □ □ □ □]  capacity=10 │
 *   │           ▲ filled    empty ▲                 │
 *   │                                               │
 *   │  Refill: +N permits every T duration          │
 *   │  Consume: -1 permit per call                  │
 *   │  Reject: when 0 permits available             │
 *   └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Before every permission check, the core first calculates how many
 * permits should have been refilled since the last refill timestamp
 * (a "lazy refill" strategy). This avoids the need for a background
 * timer thread.
 */
public final class RateLimiterCore {

    private RateLimiterCore() {
        // Utility class — not instantiable
    }

    // ======================== Refill ========================

    /**
     * Calculates the refilled snapshot based on elapsed time since the
     * last refill. This is a lazy refill — no background timer is needed.
     *
     * <p>The refill is calculated in whole periods: if 2.7 refill periods
     * have elapsed, exactly 2 periods worth of tokens are added and the
     * refill timestamp is advanced by exactly 2 periods (preserving the
     * fractional remainder for the next call).
     *
     * @param snapshot the current snapshot
     * @param config   the rate limiter configuration
     * @param now      the current timestamp
     * @return a snapshot with refilled permits and updated refill time
     */
    public static RateLimiterSnapshot refill(
            RateLimiterSnapshot snapshot,
            RateLimiterConfig config,
            Instant now) {

        long elapsedNanos = Duration.between(snapshot.lastRefillTime(), now).toNanos();
        if (elapsedNanos <= 0) {
            return snapshot;
        }

        long periodNanos = config.refillPeriod().toNanos();
        long completePeriods = elapsedNanos / periodNanos;

        if (completePeriods <= 0) {
            return snapshot;
        }

        long tokensToAdd = completePeriods * config.refillPermits();
        int newPermits = (int) Math.min(
                (long) snapshot.availablePermits() + tokensToAdd,
                config.capacity()
        );

        // Advance the refill timestamp by the number of complete periods consumed
        Instant newRefillTime = snapshot.lastRefillTime()
                .plusNanos(completePeriods * periodNanos);

        return snapshot.withRefill(newPermits, newRefillTime);
    }

    // ======================== Permission (fail-fast) ========================

    /**
     * Checks whether a single permit is available, consuming it if so.
     *
     * <p>This is a fail-fast check — if no permits are available, the
     * caller receives a {@link RateLimitPermission#rejected} result with
     * an estimated wait duration.
     *
     * @param snapshot the current snapshot
     * @param config   the rate limiter configuration
     * @param now      the current timestamp
     * @return a permission result
     */
    public static RateLimitPermission tryAcquirePermission(
            RateLimiterSnapshot snapshot,
            RateLimiterConfig config,
            Instant now) {

        RateLimiterSnapshot refilled = refill(snapshot, config, now);

        if (refilled.availablePermits() > 0) {
            return RateLimitPermission.permitted(refilled.withPermitConsumed());
        }

        Duration waitDuration = estimateWaitDuration(refilled, config, now);
        return RateLimitPermission.rejected(refilled, waitDuration);
    }

    /**
     * Checks whether {@code permits} permits are available, consuming them if so.
     *
     * @param snapshot the current snapshot
     * @param config   the rate limiter configuration
     * @param now      the current timestamp
     * @param permits  number of permits to acquire
     * @return a permission result
     */
    public static RateLimitPermission tryAcquirePermissions(
            RateLimiterSnapshot snapshot,
            RateLimiterConfig config,
            Instant now,
            int permits) {

        if (permits < 1) {
            throw new IllegalArgumentException("permits must be >= 1, got " + permits);
        }
        if (permits > config.capacity()) {
            throw new IllegalArgumentException(
                    "permits (%d) exceeds capacity (%d)".formatted(permits, config.capacity()));
        }

        RateLimiterSnapshot refilled = refill(snapshot, config, now);

        if (refilled.availablePermits() >= permits) {
            return RateLimitPermission.permitted(
                    refilled.withAvailablePermits(refilled.availablePermits() - permits));
        }

        int deficit = permits - refilled.availablePermits();
        Duration waitDuration = estimateWaitForPermits(config, deficit);
        return RateLimitPermission.rejected(refilled, waitDuration);
    }

    // ======================== Reservation (wait-capable) ========================

    /**
     * Reserves a permit, calculating the wait time the caller must honour.
     *
     * <p>Unlike {@link #tryAcquirePermission}, this method always "succeeds"
     * conceptually — the permit is consumed from a future refill cycle,
     * and the caller is told how long to wait. If the wait exceeds the
     * configured timeout, the reservation is marked as timed-out and
     * the permit is <strong>not</strong> consumed.
     *
     * <p>This allows the imperative wrapper to implement a blocking wait
     * and the reactive wrapper to use {@code Mono.delay()}.
     *
     * @param snapshot the current snapshot
     * @param config   the rate limiter configuration
     * @param now      the current timestamp
     * @param timeout  maximum acceptable wait duration
     * @return a reservation result
     */
    public static ReservationResult reservePermission(
            RateLimiterSnapshot snapshot,
            RateLimiterConfig config,
            Instant now,
            Duration timeout) {

        RateLimiterSnapshot refilled = refill(snapshot, config, now);

        if (refilled.availablePermits() > 0) {
            return ReservationResult.immediate(refilled.withPermitConsumed());
        }

        // Calculate how long until the next permit arrives
        Duration waitDuration = estimateWaitDuration(refilled, config, now);

        if (timeout.isZero() || waitDuration.compareTo(timeout) > 0) {
            return ReservationResult.timedOut(refilled, waitDuration);
        }

        // Consume a "future" permit — the caller must wait before executing
        RateLimiterSnapshot consumed = refilled.withAvailablePermits(
                refilled.availablePermits() - 1);
        return ReservationResult.delayed(consumed, waitDuration);
    }

    // ======================== Drain & Reset ========================

    /**
     * Drains all permits from the bucket. Useful for testing or
     * for implementing backpressure.
     *
     * @param snapshot the current snapshot
     * @return a snapshot with zero permits
     */
    public static RateLimiterSnapshot drain(RateLimiterSnapshot snapshot) {
        return snapshot.withAvailablePermits(0);
    }

    /**
     * Resets the bucket to its initial (full) state.
     *
     * @param config the rate limiter configuration
     * @param now    the current timestamp
     * @return a fresh snapshot
     */
    public static RateLimiterSnapshot reset(RateLimiterConfig config, Instant now) {
        return RateLimiterSnapshot.initial(config, now);
    }

    // ======================== Query helpers ========================

    /**
     * Returns the number of available permits after refilling.
     */
    public static int availablePermits(
            RateLimiterSnapshot snapshot,
            RateLimiterConfig config,
            Instant now) {

        return refill(snapshot, config, now).availablePermits();
    }

    /**
     * Estimates how long until at least one permit becomes available.
     */
    public static Duration estimateWaitDuration(
            RateLimiterSnapshot snapshot,
            RateLimiterConfig config,
            Instant now) {

        if (snapshot.availablePermits() > 0) {
            return Duration.ZERO;
        }
        return estimateWaitForPermits(config, 1);
    }

    /**
     * Estimates how long until {@code permits} permits become available
     * based on the refill rate.
     */
    static Duration estimateWaitForPermits(RateLimiterConfig config, int permits) {
        if (permits <= 0) {
            return Duration.ZERO;
        }
        // How many full refill cycles are needed?
        long cyclesNeeded = ((long) permits + config.refillPermits() - 1) / config.refillPermits();
        return config.refillPeriod().multipliedBy(cyclesNeeded);
    }
}
