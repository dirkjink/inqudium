package eu.inqudium.core.element.trafficshaper.strategy;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable state for the {@link LeakyBucketStrategy}.
 *
 * <p>Maintains a virtual timeline of evenly-spaced scheduling slots.
 * {@code nextFreeSlot} is the earliest instant at which the next request
 * may proceed. Each admitted request advances this timestamp by the
 * configured interval, creating smooth output traffic.
 *
 * <pre>
 *   Timeline:  ──|──|──|──|──|──|──►
 *                S1 S2 S3 S4 S5  ← scheduled slots
 *                         ▲
 *                    nextFreeSlot
 * </pre>
 *
 * @param nextFreeSlot  the earliest instant the next request can proceed
 * @param queueDepth    number of requests currently waiting for their slot
 * @param totalAdmitted total number of requests admitted since creation
 * @param totalRejected total number of requests rejected since creation
 * @param epoch         monotonically increasing generation counter for reset invalidation
 */
public record LeakyBucketState(
        Instant nextFreeSlot,
        int queueDepth,
        long totalAdmitted,
        long totalRejected,
        long epoch
) implements SchedulingState {

    /**
     * Creates the initial state — the first slot is immediately available.
     */
    public static LeakyBucketState initial(Instant now) {
        return new LeakyBucketState(now, 0, 0, 0, 0L);
    }

    // --- Wither methods for immutable updates ---

    /**
     * Schedules a delayed request: advances the next free slot by the given interval
     * and increments the queue depth.
     */
    public LeakyBucketState withRequestScheduled(Duration interval) {
        return new LeakyBucketState(
                nextFreeSlot.plus(interval),
                queueDepth + 1,
                totalAdmitted + 1,
                totalRejected,
                epoch
        );
    }

    /**
     * Schedules an immediate request: advances the next free slot by the given
     * interval but does NOT increment the queue depth.
     */
    public LeakyBucketState withRequestScheduledImmediate(Duration interval) {
        return new LeakyBucketState(
                nextFreeSlot.plus(interval),
                queueDepth,
                totalAdmitted + 1,
                totalRejected,
                epoch
        );
    }

    /**
     * Records that a queued request has started executing (left the queue).
     */
    public LeakyBucketState withRequestDequeued() {
        return new LeakyBucketState(
                nextFreeSlot,
                Math.max(0, queueDepth - 1),
                totalAdmitted,
                totalRejected,
                epoch
        );
    }

    /**
     * Records a rejected request (does not affect the scheduling timeline).
     */
    public LeakyBucketState withRequestRejected() {
        return new LeakyBucketState(nextFreeSlot, queueDepth, totalAdmitted, totalRejected + 1, epoch);
    }

    /**
     * Resets the next free slot to the given time.
     */
    public LeakyBucketState withNextFreeSlot(Instant slot) {
        return new LeakyBucketState(slot, queueDepth, totalAdmitted, totalRejected, epoch);
    }

    /**
     * Creates a fresh state with the epoch incremented and queue cleared.
     */
    public LeakyBucketState withNextEpoch(Instant now) {
        return new LeakyBucketState(now, 0, totalAdmitted, totalRejected, epoch + 1);
    }

    // --- Query helpers ---

    /**
     * Returns the wait duration for a request arriving at the given instant.
     */
    public Duration waitDurationFor(Instant now) {
        Duration wait = Duration.between(now, nextFreeSlot);
        return wait.isNegative() ? Duration.ZERO : wait;
    }

    @Override
    public Duration projectedTailWait(Instant now) {
        if (nextFreeSlot.isBefore(now) || nextFreeSlot.equals(now)) {
            return Duration.ZERO;
        }
        return Duration.between(now, nextFreeSlot);
    }
}
