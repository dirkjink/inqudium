package eu.inqudium.core.circuitbreaker;

/**
 * Count-based sliding window using a circular buffer (ADR-016).
 *
 * <p>Records the last N call outcomes. Failure and slow call rates are computed
 * from running counters updated incrementally — no full-buffer scan on each call.
 *
 * <p>Time complexity: O(1) per {@link #record}. No allocation per call.
 *
 * <p><strong>Not thread-safe.</strong> The paradigm module provides synchronization
 * (ReentrantLock, Mutex, etc.).
 *
 * @since 0.1.0
 */
public final class CountBasedSlidingWindow implements SlidingWindow {

    private final int size;
    private final long slowCallThresholdNanos;
    private final CallOutcome[] buffer;
    private int head;
    private int count;
    private int failures;
    private int slowCalls;

    /**
     * Creates a new count-based sliding window.
     *
     * @param size                    the window size (number of calls to track)
     * @param slowCallThresholdNanos  calls exceeding this duration (nanos) are counted as slow
     */
    public CountBasedSlidingWindow(int size, long slowCallThresholdNanos) {
        if (size <= 0) throw new IllegalArgumentException("Window size must be positive, got: " + size);
        this.size = size;
        this.slowCallThresholdNanos = slowCallThresholdNanos;
        this.buffer = new CallOutcome[size];
        this.head = 0;
        this.count = 0;
        this.failures = 0;
        this.slowCalls = 0;
    }

    @Override
    public WindowSnapshot record(CallOutcome outcome) {
        // Evict oldest entry if buffer is full
        if (count >= size) {
            var evicted = buffer[head];
            if (!evicted.success()) failures--;
            if (evicted.durationNanos() > slowCallThresholdNanos) slowCalls--;
        }

        // Record new entry
        buffer[head] = outcome;
        head = (head + 1) % size;
        if (count < size) count++;
        if (!outcome.success()) failures++;
        if (outcome.durationNanos() > slowCallThresholdNanos) slowCalls++;

        return buildSnapshot();
    }

    @Override
    public WindowSnapshot snapshot() {
        return buildSnapshot();
    }

    @Override
    public void reset() {
        for (int i = 0; i < size; i++) {
            buffer[i] = null;
        }
        head = 0;
        count = 0;
        failures = 0;
        slowCalls = 0;
    }

    private WindowSnapshot buildSnapshot() {
        if (count == 0) {
            return new WindowSnapshot(0f, 0f, 0, 0, 0, 0, size);
        }
        float failureRate = (failures * 100.0f) / count;
        float slowCallRate = (slowCalls * 100.0f) / count;
        int successful = count - failures;
        return new WindowSnapshot(failureRate, slowCallRate, count, failures, slowCalls, successful, size);
    }
}
