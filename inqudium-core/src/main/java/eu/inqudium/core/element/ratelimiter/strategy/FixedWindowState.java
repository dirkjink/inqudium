package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimiterState;

import java.time.Instant;

public record FixedWindowState(
        int usedPermits,
        Instant windowStart,
        long epoch
) implements RateLimiterState {

    public FixedWindowState withUsedPermits(int used) {
        return new FixedWindowState(used, windowStart, epoch);
    }

    @Override
    public FixedWindowState withNextEpoch(Instant now) {
        return new FixedWindowState(usedPermits, now, epoch + 1);
    }

    public FixedWindowState withNextEpoch(int used, Instant newWindowStart) {
        return new FixedWindowState(used, newWindowStart, epoch + 1);
    }
}
