package eu.inqudium.core.ratelimiter;

import java.time.Duration;

/**
 * Result of a permit reservation.
 *
 * <p>Unlike {@link RateLimitPermission} which is a simple check,
 * a reservation always succeeds but may require the caller to wait.
 * The reservation has already consumed the permit from the snapshot,
 * and the caller must honour the {@link #waitDuration()} before
 * executing the protected operation.
 *
 * <p>If the required wait exceeds the caller's configured timeout,
 * the reservation is marked as {@code timedOut} and the permit is
 * <strong>not</strong> consumed (the original snapshot is preserved).
 *
 * @param snapshot     the updated snapshot (permit consumed, or unchanged on timeout)
 * @param waitDuration how long the caller must wait before proceeding
 * @param timedOut     whether the wait would exceed the configured timeout
 */
public record ReservationResult(
        RateLimiterSnapshot snapshot,
        Duration waitDuration,
        boolean timedOut
) {

    /**
     * Creates a successful reservation with no wait required.
     */
    public static ReservationResult immediate(RateLimiterSnapshot snapshot) {
        return new ReservationResult(snapshot, Duration.ZERO, false);
    }

    /**
     * Creates a reservation that requires the caller to wait.
     */
    public static ReservationResult delayed(RateLimiterSnapshot snapshot, Duration waitDuration) {
        return new ReservationResult(snapshot, waitDuration, false);
    }

    /**
     * Creates a timed-out reservation — the permit was NOT consumed.
     */
    public static ReservationResult timedOut(RateLimiterSnapshot snapshot, Duration wouldHaveWaited) {
        return new ReservationResult(snapshot, wouldHaveWaited, true);
    }
}
