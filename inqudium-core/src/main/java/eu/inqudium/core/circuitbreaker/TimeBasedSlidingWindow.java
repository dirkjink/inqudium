package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.InqClock;

import java.time.Duration;
import java.time.Instant;

/**
 * Time-based sliding window using partial time buckets (ADR-016).
 *
 * <p>Divides the configured window duration into 1-second buckets. Each bucket
 * aggregates call counts for its time slice. Buckets older than the window
 * duration are evicted on the next access.
 *
 * <p>Time is provided by {@link InqClock} — no {@code Instant.now()} calls.
 * Fully deterministic in tests.
 *
 * <p><strong>Not thread-safe.</strong> The paradigm module provides synchronization.
 *
 * @since 0.1.0
 */
public final class TimeBasedSlidingWindow implements SlidingWindow {

    private final int windowSizeSeconds;
    private final long slowCallThresholdNanos;
    private final InqClock clock;
    private final Bucket[] buckets;
    private int currentBucketIndex;
    private long currentBucketEpochSecond;

    /**
     * Creates a new time-based sliding window.
     *
     * @param windowSizeSeconds      the window duration in seconds
     * @param slowCallThresholdNanos calls exceeding this duration (nanos) are counted as slow
     * @param clock                  the time source
     */
    public TimeBasedSlidingWindow(int windowSizeSeconds, long slowCallThresholdNanos, InqClock clock) {
        if (windowSizeSeconds <= 0) throw new IllegalArgumentException("Window size must be positive, got: " + windowSizeSeconds);
        this.windowSizeSeconds = windowSizeSeconds;
        this.slowCallThresholdNanos = slowCallThresholdNanos;
        this.clock = clock;
        this.buckets = new Bucket[windowSizeSeconds];
        for (int i = 0; i < windowSizeSeconds; i++) {
            buckets[i] = new Bucket();
        }
        var now = clock.instant();
        this.currentBucketEpochSecond = now.getEpochSecond();
        this.currentBucketIndex = 0;
    }

    @Override
    public WindowSnapshot record(CallOutcome outcome) {
        advanceTo(clock.instant());
        var bucket = buckets[currentBucketIndex];
        bucket.totalCalls++;
        if (!outcome.success()) bucket.failures++;
        if (outcome.durationNanos() > slowCallThresholdNanos) bucket.slowCalls++;
        return buildSnapshot();
    }

    @Override
    public WindowSnapshot snapshot() {
        advanceTo(clock.instant());
        return buildSnapshot();
    }

    @Override
    public void reset() {
        for (var bucket : buckets) {
            bucket.reset();
        }
        var now = clock.instant();
        currentBucketEpochSecond = now.getEpochSecond();
        currentBucketIndex = 0;
    }

    private void advanceTo(Instant now) {
        long nowEpochSecond = now.getEpochSecond();
        long elapsed = nowEpochSecond - currentBucketEpochSecond;

        if (elapsed <= 0) {
            return; // still in the same bucket
        }

        if (elapsed >= windowSizeSeconds) {
            // Entire window has elapsed — clear everything
            for (var bucket : buckets) {
                bucket.reset();
            }
            currentBucketIndex = 0;
        } else {
            // Advance bucket by bucket, clearing old ones
            for (long i = 0; i < elapsed; i++) {
                currentBucketIndex = (currentBucketIndex + 1) % windowSizeSeconds;
                buckets[currentBucketIndex].reset();
            }
        }
        currentBucketEpochSecond = nowEpochSecond;
    }

    private WindowSnapshot buildSnapshot() {
        int totalCalls = 0;
        int failures = 0;
        int slowCalls = 0;

        for (var bucket : buckets) {
            totalCalls += bucket.totalCalls;
            failures += bucket.failures;
            slowCalls += bucket.slowCalls;
        }

        if (totalCalls == 0) {
            return new WindowSnapshot(0f, 0f, 0, 0, 0, 0, windowSizeSeconds);
        }

        float failureRate = (failures * 100.0f) / totalCalls;
        float slowCallRate = (slowCalls * 100.0f) / totalCalls;
        int successful = totalCalls - failures;
        return new WindowSnapshot(failureRate, slowCallRate, totalCalls, failures, slowCalls, successful, windowSizeSeconds);
    }

    private static final class Bucket {
        int totalCalls;
        int failures;
        int slowCalls;

        void reset() {
            totalCalls = 0;
            failures = 0;
            slowCalls = 0;
        }
    }
}
