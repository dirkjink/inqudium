package eu.inqudium.config.snapshot;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the CoDel (Controlled Delay) bulkhead strategy.
 *
 * <p>CoDel rejects calls that have been queued longer than {@code targetDelay} for more than
 * {@code interval} consecutive samples — the algorithm trades raw queue-length signals for a
 * latency-based signal that responds to overload patterns the semaphore strategy cannot
 * detect. Both fields must be strictly positive: zero or negative would degenerate the
 * algorithm into either "drop everything" or "drop nothing" and is treated as a configuration
 * mistake rather than an extreme operating mode.
 *
 * @param targetDelay the latency budget above which queued calls become drop candidates;
 *                    non-null, strictly positive.
 * @param interval    the consecutive-overshoot window required before a drop decision is
 *                    taken; non-null, strictly positive.
 */
public record CoDelStrategyConfig(Duration targetDelay, Duration interval)
        implements BulkheadStrategyConfig {

    public CoDelStrategyConfig {
        Objects.requireNonNull(targetDelay, "targetDelay");
        Objects.requireNonNull(interval, "interval");
        if (targetDelay.isZero() || targetDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "targetDelay must be strictly positive, got: " + targetDelay);
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "interval must be strictly positive, got: " + interval);
        }
    }
}
